package com.arclink.protocol;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter (CLAUDE.md 3.5): base 1s, cap 30s,
 * delay drawn uniformly from [0, min(cap, base * 2^attempt)] so a mass
 * reconnect after a server restart doesn't thunder-herd.
 */
public final class ReconnectPolicy {
	private static final long BASE_MS = 1_000L;
	private static final long CAP_MS = 30_000L;

	public long nextDelayMillis(int attempt) {
		long exp = Math.min(CAP_MS, BASE_MS * (1L << Math.min(attempt, 20)));
		return ThreadLocalRandom.current().nextLong(exp + 1);
	}
}
