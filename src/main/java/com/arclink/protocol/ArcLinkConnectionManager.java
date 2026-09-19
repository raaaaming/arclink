package com.arclink.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Orchestrates the full ARCLink connection lifecycle (CLAUDE.md 3): connect,
 * mutual challenge-response handshake + version negotiation, heartbeat,
 * disconnect detection, and reconnect-with-resync. One instance == one
 * logical connection to one ARCLink server.
 *
 * <p>Runs entirely on a background thread; inbound application commands are
 * always handed to {@code mainThreadExecutor} before {@link CommandRegistry}
 * dispatch, so individual {@link Command}s never have to remember to hop
 * threads themselves.
 */
public final class ArcLinkConnectionManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("ARCLink");

	public static final ProtocolVersion PROTOCOL_VERSION = new ProtocolVersion(1, 0, 0);

	private static final String TYPE_AUTH_CHALLENGE = "auth_challenge";
	private static final String TYPE_AUTH_RESPONSE = "auth_response";
	private static final String TYPE_AUTH_RESULT = "auth_result";
	private static final String TYPE_PING = "ping";
	private static final String TYPE_PONG = "pong";
	private static final String TYPE_RESYNC_REQUEST = "resync_request";

	private static final long PING_INTERVAL_MS = 5_000L;
	private static final long PING_TIMEOUT_MS = 15_000L;
	// ponytail: fixed-size LRU id cache bounds memory but caps dedupe window at
	// this many recent messages; widen if a burst legitimately exceeds it.
	private static final int DEDUPE_CACHE_SIZE = 256;

	public enum State {DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, CLOSED}

	public record Endpoint(String host, int port, boolean tls, byte[] presharedSecret) {
		public URI toUri() {
			return URI.create((tls ? "wss" : "ws") + "://" + host + ":" + port + "/arclink");
		}
	}

	private final Endpoint endpoint;
	private final CommandRegistry registry;
	private final Executor mainThreadExecutor;
	private final Consumer<String> userFacingErrorSink;
	private final Supplier<String> playerNameSupplier;
	private final Gson gson = new Gson();
	private final ReconnectPolicy backoff = new ReconnectPolicy();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "arclink-net");
		t.setDaemon(true);
		return t;
	});
	private final Set<String> seenMessageIds = Collections.synchronizedSet(
			Collections.newSetFromMap(new LinkedHashMap<>() {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > DEDUPE_CACHE_SIZE;
				}
			}));

	private volatile ArcLinkConnection connection;
	private volatile State state = State.DISCONNECTED;
	private volatile String sessionToken;
	private volatile byte[] pendingClientNonce;
	private volatile long lastPongAtMillis;
	private volatile boolean stopping;
	private volatile ScheduledFuture<?> heartbeatTask;
	private final AtomicInteger reconnectAttempt = new AtomicInteger();

	public ArcLinkConnectionManager(Endpoint endpoint, CommandRegistry registry, Executor mainThreadExecutor,
			Consumer<String> userFacingErrorSink, Supplier<String> playerNameSupplier) {
		this.endpoint = endpoint;
		this.registry = registry;
		this.mainThreadExecutor = mainThreadExecutor;
		this.userFacingErrorSink = userFacingErrorSink == null ? s -> {
		} : userFacingErrorSink;
		this.playerNameSupplier = playerNameSupplier;
	}

	public State state() {
		return state;
	}

	public void start() {
		stopping = false;
		connect();
	}

	public void stop() {
		stopping = true;
		stopHeartbeat();
		ArcLinkConnection c = connection;
		if (c != null) {
			c.close();
		}
		scheduler.shutdownNow();
		state = State.CLOSED;
	}

	/** Attaches the current session token and sends; drops silently if not connected. */
	public void send(ArcLinkMessage message) {
		if (state != State.CONNECTED) {
			LOGGER.warn("ARCLink: dropping outbound '{}' — not connected", message.type);
			return;
		}
		sendRaw(message.withSessionToken(sessionToken));
	}

	private void sendRaw(ArcLinkMessage message) {
		ArcLinkConnection c = connection;
		if (c != null) {
			c.send(message.toJson(gson));
		}
	}

	private void connect() {
		state = State.CONNECTING;
		ArcLinkConnection c = new ArcLinkConnection();
		connection = c;
		c.connect(endpoint.toUri(), new ArcLinkConnection.Listener() {
			@Override
			public void onMessage(String rawJson) {
				handleRawMessage(rawJson);
			}

			@Override
			public void onClosed(int statusCode, String reason) {
				handleDisconnect("closed(" + statusCode + "): " + reason);
			}

			@Override
			public void onFailed(Throwable error) {
				handleDisconnect("failed: " + error);
			}
		}).whenComplete((ignored, error) -> {
			if (error != null) {
				handleDisconnect("connect failed: " + error);
			} else {
				state = State.AUTHENTICATING;
				LOGGER.info("ARCLink: socket open, awaiting server auth challenge");
			}
		});
	}

	private void handleRawMessage(String rawJson) {
		ArcLinkMessage message;
		try {
			message = ArcLinkMessage.fromJson(gson, rawJson);
		} catch (JsonParseException e) {
			LOGGER.warn("ARCLink: dropping malformed message: {}", e.getMessage());
			return;
		}
		try {
			switch (message.type) {
				case TYPE_AUTH_CHALLENGE -> onAuthChallenge(message);
				case TYPE_AUTH_RESULT -> onAuthResult(message);
				case TYPE_PONG -> lastPongAtMillis = System.currentTimeMillis();
				default -> onApplicationMessage(message);
			}
		} catch (HandshakeException e) {
			LOGGER.error("ARCLink: {}", e.getMessage());
			userFacingErrorSink.accept(e.getMessage());
			ArcLinkConnection c = connection;
			if (c != null) {
				c.close();
			}
		}
	}

	private void onAuthChallenge(ArcLinkMessage message) throws HandshakeException {
		ProtocolVersion remoteVersion = parseRemoteVersion(message.version);
		ProtocolVersion.Negotiation negotiation = PROTOCOL_VERSION.negotiate(remoteVersion);
		if (!negotiation.compatible()) {
			throw new HandshakeException(negotiation.reason());
		}

		byte[] nonce = Base64.getDecoder().decode(requirePayloadField(message, "nonce"));
		String signature = AuthCodec.sign(endpoint.presharedSecret(), nonce);

		byte[] clientNonce = AuthCodec.randomNonce(24);
		pendingClientNonce = clientNonce;

		JsonObject payload = new JsonObject();
		payload.addProperty("signature", signature);
		payload.addProperty("clientNonce", Base64.getEncoder().encodeToString(clientNonce));
		payload.addProperty("playerName", playerNameSupplier.get());
		sendRaw(ArcLinkMessage.create(TYPE_AUTH_RESPONSE, PROTOCOL_VERSION.toString(), payload));
	}

	private void onAuthResult(ArcLinkMessage message) throws HandshakeException {
		boolean ok = message.payload.has("ok") && message.payload.get("ok").getAsBoolean();
		if (!ok) {
			String reason = message.payload.has("reason")
					? message.payload.get("reason").getAsString()
					: "server rejected authentication";
			throw new HandshakeException("ARCLink: authentication rejected — " + reason);
		}

		byte[] clientNonce = pendingClientNonce;
		String serverProof = requirePayloadField(message, "serverProof");
		if (clientNonce == null || !AuthCodec.verify(endpoint.presharedSecret(), clientNonce, serverProof)) {
			throw new HandshakeException(
					"ARCLink: server failed to prove it holds the shared secret — refusing to trust this connection");
		}
		pendingClientNonce = null;
		sessionToken = requirePayloadField(message, "sessionToken");

		state = State.CONNECTED;
		reconnectAttempt.set(0);
		LOGGER.info("ARCLink: authenticated (mutual), session established");

		startHeartbeat();
		// Always resync after (re)authenticating: on a fresh connect the server
		// just answers "nothing to restore", on a reconnect it's mandatory (3.5).
		send(ArcLinkMessage.create(TYPE_RESYNC_REQUEST, PROTOCOL_VERSION.toString(), new JsonObject()));
	}

	private void onApplicationMessage(ArcLinkMessage message) {
		if (state != State.CONNECTED) {
			LOGGER.warn("ARCLink: dropping '{}' — not authenticated yet", message.type);
			return;
		}
		if (message.id != null && !seenMessageIds.add(message.id)) {
			return; // duplicate delivery of an already-processed signal (4.3 idempotency)
		}
		mainThreadExecutor.execute(() -> {
			if (!registry.dispatch(message)) {
				LOGGER.debug("ARCLink: no handler registered for type '{}'", message.type);
			}
		});
	}

	private void startHeartbeat() {
		lastPongAtMillis = System.currentTimeMillis();
		heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeatTick,
				PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	private void stopHeartbeat() {
		ScheduledFuture<?> task = heartbeatTask;
		if (task != null) {
			task.cancel(false);
		}
	}

	private void heartbeatTick() {
		if (state != State.CONNECTED) {
			return;
		}
		long silence = System.currentTimeMillis() - lastPongAtMillis;
		if (silence > PING_TIMEOUT_MS) {
			LOGGER.warn("ARCLink: no pong for {}ms, treating connection as dead", silence);
			handleDisconnect("heartbeat timeout");
			return;
		}
		send(ArcLinkMessage.create(TYPE_PING, PROTOCOL_VERSION.toString(), new JsonObject()));
	}

	private void handleDisconnect(String reason) {
		if (stopping || state == State.RECONNECTING || state == State.CLOSED) {
			return;
		}
		LOGGER.warn("ARCLink: disconnected ({}), scheduling reconnect", reason);
		stopHeartbeat();
		sessionToken = null;
		pendingClientNonce = null;
		ArcLinkConnection c = connection;
		if (c != null) {
			c.close();
		}
		state = State.RECONNECTING;
		int attempt = reconnectAttempt.getAndIncrement();
		long delay = backoff.nextDelayMillis(attempt);
		LOGGER.info("ARCLink: reconnecting in {}ms (attempt {})", delay, attempt + 1);
		scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private static String requirePayloadField(ArcLinkMessage message, String field) throws HandshakeException {
		if (!message.payload.has(field)) {
			throw new HandshakeException("ARCLink: '" + message.type + "' missing required field '" + field + "'");
		}
		return message.payload.get(field).getAsString();
	}

	private static ProtocolVersion parseRemoteVersion(String version) throws HandshakeException {
		try {
			return ProtocolVersion.parse(version);
		} catch (IllegalArgumentException e) {
			throw new HandshakeException("ARCLink: server sent an unparsable protocol version: " + version);
		}
	}
}
