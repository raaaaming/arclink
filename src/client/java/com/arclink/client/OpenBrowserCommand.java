package com.arclink.client;

import com.arclink.protocol.ArcLinkMessage;
import com.arclink.protocol.Command;
import com.cinemamod.mcef.MCEF;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntSupplier;

/**
 * Handles the server-pushed {@code open_browser} signal:
 * {@code {"type":"open_browser","payload":{"path":"/f/...&sig=..."}}}.
 *
 * <p>{@code path} is the path+query fragment {@code ArcLinkService.serveFile()} (plugin side)
 * signs — see that method's javadoc for the contract. This class combines it with the
 * already-authenticated connection's own configured {@code host}/{@code tls} and the httpPort
 * advertised at handshake ({@link com.arclink.protocol.ArcLinkConnectionManager#httpPort()}) to
 * build the final URL, then opens it in an MCEF-backed {@link ArcLinkBrowserScreen}.
 *
 * <p>Deliberately never trusts a server-supplied host/scheme — only the path fragment is
 * server-controlled, so a compromised/malicious signal can at most point at another path on our
 * own already-authenticated file server, never an arbitrary external URL.
 */
public final class OpenBrowserCommand implements Command {
	private static final Logger LOGGER = LoggerFactory.getLogger("ARCLink");

	private final MinecraftClient client;
	private final String host;
	private final boolean tls;
	private final IntSupplier httpPortSupplier;

	public OpenBrowserCommand(MinecraftClient client, String host, boolean tls, IntSupplier httpPortSupplier) {
		this.client = client;
		this.host = host;
		this.tls = tls;
		this.httpPortSupplier = httpPortSupplier;
	}

	@Override
	public void execute(ArcLinkMessage message) {
		LOGGER.info("ARCLink: 'open_browser' command received, payload={}", message.payload);
		if (!message.payload.has("path")) {
			LOGGER.warn("ARCLink: 'open_browser' missing required field 'path' — dropping");
			return;
		}
		String path = message.payload.get("path").getAsString();

		// Graceful degradation (CLAUDE.md 5): MCEF may not be bundled, or its CEF runtime may
		// still be downloading/starting — either way we drop the signal instead of crashing.
		if (!MCEF.isInitialized()) {
			LOGGER.warn("ARCLink: 'open_browser' received but MCEF isn't initialized yet — dropping");
			if (client.player != null) {
				client.player.sendMessage(
						Text.literal("[ARCLink] Browser UI unavailable: MCEF is not ready yet."), false);
			}
			return;
		}

		int httpPort = httpPortSupplier.getAsInt();
		if (httpPort <= 0) {
			LOGGER.warn("ARCLink: 'open_browser' received but no httpPort was advertised at handshake "
					+ "(server plugin predates the file server, or it's disabled) — dropping");
			if (client.player != null) {
				client.player.sendMessage(
						Text.literal("[ARCLink] Browser UI unavailable: no file server port advertised."), false);
			}
			return;
		}

		String url = (tls ? "https" : "http") + "://" + host + ":" + httpPort + path;
		LOGGER.info("ARCLink: opening browser screen at {}", url);
		// 진단용 안전망: MCEF.createBrowser()/setScreen()은 CEF 상태에 따라 예외를 던질 수 있고,
		// 잡지 않으면 그 예외가 호출자(ArcLinkConnectionManager의 mainThreadExecutor) 쪽에서만
		// 잡혀 사용자는 화면도 로그도 전혀 못 볼 수 있다 — 가장 바깥쪽인 여기서 잡아 로그로 남긴다.
		try {
			client.setScreen(new ArcLinkBrowserScreen(url));
			LOGGER.info("ARCLink: setScreen(ArcLinkBrowserScreen) returned without throwing");
		} catch (Throwable t) {
			LOGGER.error("ARCLink: failed to open browser screen for url={}", url, t);
			if (client.player != null) {
				client.player.sendMessage(
						Text.literal("[ARCLink] Failed to open browser UI: " + t.getClass().getSimpleName()
								+ (t.getMessage() != null ? " - " + t.getMessage() : "")), false);
			}
		}
	}
}
