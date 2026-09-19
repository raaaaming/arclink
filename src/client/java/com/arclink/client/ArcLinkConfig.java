package com.arclink.client;

import com.arclink.protocol.AuthCodec;
import com.arclink.protocol.ArcLinkConnectionManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Plain JSON config at {@code config/arclink.json}. The pre-shared secret is
 * generated locally on first run (never hardcoded, never transmitted) — copy
 * the printed hex string into the server plugin's config to pair them.
 */
public final class ArcLinkConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("ARCLink");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String host = "";
	public int port = 25566;
	public boolean tls = false;
	public String presharedKeyHex = "";

	public static ArcLinkConfig loadOrCreate(Path path) {
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ArcLinkConfig loaded = GSON.fromJson(reader, ArcLinkConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read " + path, e);
			}
		}

		ArcLinkConfig fresh = new ArcLinkConfig();
		fresh.presharedKeyHex = HexFormat.of().formatHex(AuthCodec.randomNonce(32));
		save(path, fresh);
		LOGGER.warn("ARCLink: generated new config at {} — set 'host'/'port' and copy 'presharedKeyHex' into "
				+ "the server plugin config before it will connect.", path);
		return fresh;
	}

	private static void save(Path path, ArcLinkConfig config) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write " + path, e);
		}
	}

	public boolean isConfigured() {
		return !host.isBlank() && !presharedKeyHex.isBlank();
	}

	public ArcLinkConnectionManager.Endpoint toEndpoint() {
		return new ArcLinkConnectionManager.Endpoint(host, port, tls, HexFormat.of().parseHex(presharedKeyHex));
	}
}
