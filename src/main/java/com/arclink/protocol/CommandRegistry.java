package com.arclink.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps a message's {@code type} tag to the {@link Command} that handles it. */
public final class CommandRegistry {
	private final Map<String, Command> handlers = new ConcurrentHashMap<>();

	public void register(String type, Command command) {
		handlers.put(type, command);
	}

	/** @return true if a handler was found and run for {@code message.type}. */
	public boolean dispatch(ArcLinkMessage message) {
		Command command = handlers.get(message.type);
		if (command == null) {
			return false;
		}
		command.execute(message);
		return true;
	}
}
