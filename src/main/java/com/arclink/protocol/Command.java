package com.arclink.protocol;

/** A single executable server signal (CLAUDE.md 4.2 command pattern). */
@FunctionalInterface
public interface Command {
	void execute(ArcLinkMessage message);
}
