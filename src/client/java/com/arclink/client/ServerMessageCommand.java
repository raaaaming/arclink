package com.arclink.client;

import com.arclink.protocol.ArcLinkMessage;
import com.arclink.protocol.Command;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Minimal vertical-slice command: server sends {@code {"type":"server_message",
 * "payload":{"text":"..."}}} and it shows up in chat. Proves the command-pattern
 * dispatch path end to end; real ARCLink UI features register their own
 * {@link Command}s the same way once the server plugin defines them.
 */
public final class ServerMessageCommand implements Command {
	private final MinecraftClient client;

	public ServerMessageCommand(MinecraftClient client) {
		this.client = client;
	}

	@Override
	public void execute(ArcLinkMessage message) {
		if (!message.payload.has("text")) {
			return;
		}
		String text = message.payload.get("text").getAsString();
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[ARCLink] " + text), false);
		}
	}
}
