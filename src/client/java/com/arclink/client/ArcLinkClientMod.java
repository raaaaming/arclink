package com.arclink.client;

import com.arclink.protocol.ArcLinkConnectionManager;
import com.arclink.protocol.CommandRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArcLinkClientMod implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("ARCLink");

	private ArcLinkConnectionManager connectionManager;

	@Override
	public void onInitializeClient() {
		ArcLinkConfig config = ArcLinkConfig.loadOrCreate(
				FabricLoader.getInstance().getConfigDir().resolve("arclink.json"));

		if (!config.isConfigured()) {
			LOGGER.warn("ARCLink: 'host' not set in config/arclink.json — skipping connection.");
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		CommandRegistry registry = new CommandRegistry();
		registry.register("server_message", new ServerMessageCommand(client));

		connectionManager = new ArcLinkConnectionManager(
				config.toEndpoint(),
				registry,
				client::execute,
				message -> {
					if (client.player != null) {
						client.player.sendMessage(Text.literal("[ARCLink] " + message), false);
					}
				});

		ClientLifecycleEvents.CLIENT_STARTED.register(c -> connectionManager.start());
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> connectionManager.stop());
	}
}
