package com.arclink.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.UUID;

/**
 * Wire envelope for every ARCLink signal (CLAUDE.md 4.1): every message carries
 * {@code type} and {@code version} from day one so unknown fields/types can be
 * safely ignored by older/newer peers.
 */
public final class ArcLinkMessage {
	public final String type;
	public final String version;
	public final String id;
	public final String sessionToken;
	public final JsonObject payload;

	public ArcLinkMessage(String type, String version, String id, String sessionToken, JsonObject payload) {
		this.type = type;
		this.version = version;
		this.id = id;
		this.sessionToken = sessionToken;
		this.payload = payload == null ? new JsonObject() : payload;
	}

	public static ArcLinkMessage create(String type, String version, JsonObject payload) {
		return new ArcLinkMessage(type, version, UUID.randomUUID().toString(), null, payload);
	}

	public ArcLinkMessage withSessionToken(String token) {
		return new ArcLinkMessage(type, version, id, token, payload);
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}

	public static ArcLinkMessage fromJson(Gson gson, String json) {
		ArcLinkMessage message;
		try {
			message = gson.fromJson(json, ArcLinkMessage.class);
		} catch (RuntimeException e) {
			throw new JsonParseException("Malformed ARCLink message", e);
		}
		if (message == null || message.type == null || message.version == null) {
			throw new JsonParseException("ARCLink message missing required 'type'/'version': " + json);
		}
		return message;
	}
}
