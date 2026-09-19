package com.arclink.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArcLinkMessageTest {
	private final Gson gson = new Gson();

	@Test
	void roundTripsThroughJson() {
		JsonObject payload = new JsonObject();
		payload.addProperty("text", "hello");
		ArcLinkMessage original = ArcLinkMessage.create("server_message", "1.0.0", payload)
				.withSessionToken("tok-123");

		ArcLinkMessage parsed = ArcLinkMessage.fromJson(gson, original.toJson(gson));

		assertEquals(original.type, parsed.type);
		assertEquals(original.version, parsed.version);
		assertEquals(original.id, parsed.id);
		assertEquals(original.sessionToken, parsed.sessionToken);
		assertEquals("hello", parsed.payload.get("text").getAsString());
	}

	@Test
	void rejectsMessageMissingType() {
		assertThrows(JsonParseException.class,
				() -> ArcLinkMessage.fromJson(gson, "{\"version\":\"1.0.0\"}"));
	}

	@Test
	void rejectsGarbageJson() {
		assertThrows(JsonParseException.class,
				() -> ArcLinkMessage.fromJson(gson, "{not valid json"));
	}
}
