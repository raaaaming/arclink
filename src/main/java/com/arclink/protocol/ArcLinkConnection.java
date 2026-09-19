package com.arclink.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Thin wrapper around the JDK's built-in {@link WebSocket} client (Java 11+
 * stdlib — no extra WebSocket dependency needed). Reassembles fragmented text
 * frames into complete JSON documents before handing them to the listener.
 */
public final class ArcLinkConnection {

	public interface Listener {
		void onMessage(String rawJson);

		void onClosed(int statusCode, String reason);

		void onFailed(Throwable error);
	}

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final StringBuilder textBuffer = new StringBuilder();
	private volatile WebSocket webSocket;

	public CompletableFuture<Void> connect(URI uri, Listener listener) {
		return httpClient.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(uri, new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket ws) {
						ws.request(1);
					}

					@Override
					public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
						textBuffer.append(data);
						if (last) {
							String message = textBuffer.toString();
							textBuffer.setLength(0);
							listener.onMessage(message);
						}
						ws.request(1);
						return null;
					}

					@Override
					public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
						listener.onClosed(statusCode, reason);
						return null;
					}

					@Override
					public void onError(WebSocket ws, Throwable error) {
						listener.onFailed(error);
					}
				})
				.thenAccept(ws -> this.webSocket = ws);
	}

	public void send(String json) {
		WebSocket ws = this.webSocket;
		if (ws != null && !ws.isOutputClosed()) {
			ws.sendText(json, true);
		}
	}

	public void close() {
		WebSocket ws = this.webSocket;
		this.webSocket = null;
		if (ws != null && !ws.isOutputClosed()) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
		}
	}
}
