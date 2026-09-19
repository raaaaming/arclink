package com.arclink.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {

	@Test
	void dispatchesToRegisteredHandlerByType() {
		CommandRegistry registry = new CommandRegistry();
		AtomicReference<String> received = new AtomicReference<>();
		registry.register("greet", message -> received.set(message.payload.get("who").getAsString()));

		JsonObject payload = new JsonObject();
		payload.addProperty("who", "world");
		boolean handled = registry.dispatch(ArcLinkMessage.create("greet", "1.0.0", payload));

		assertTrue(handled);
		assertEquals("world", received.get());
	}

	@Test
	void unknownTypeIsReportedUnhandled() {
		CommandRegistry registry = new CommandRegistry();

		boolean handled = registry.dispatch(ArcLinkMessage.create("nope", "1.0.0", new JsonObject()));

		assertFalse(handled);
	}
}
