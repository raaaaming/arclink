package com.arclink.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectPolicyTest {

	@Test
	void delayStaysWithinExponentialCapAndNeverGoesNegative() {
		ReconnectPolicy policy = new ReconnectPolicy();

		for (int attempt = 0; attempt < 12; attempt++) {
			long expectedCeiling = Math.min(30_000L, 1_000L * (1L << Math.min(attempt, 20)));
			for (int sample = 0; sample < 20; sample++) {
				long delay = policy.nextDelayMillis(attempt);
				assertTrue(delay >= 0, "delay must not be negative");
				assertTrue(delay <= expectedCeiling, "delay must not exceed the exponential ceiling");
				assertTrue(delay <= 30_000L, "delay must never exceed the 30s cap");
			}
		}
	}
}
