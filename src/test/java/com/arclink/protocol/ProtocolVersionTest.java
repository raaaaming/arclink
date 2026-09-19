package com.arclink.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolVersionTest {

	@Test
	void sameMajorDegradesToLowerMinor() {
		var local = new ProtocolVersion(1, 4, 0);
		var remote = new ProtocolVersion(1, 2, 7);

		var result = local.negotiate(remote);

		assertTrue(result.compatible());
		assertEquals(2, result.negotiatedMinor());
	}

	@Test
	void differingMajorIsRejectedWithUserFacingReason() {
		var local = new ProtocolVersion(1, 0, 0);
		var remote = new ProtocolVersion(2, 0, 0);

		var result = local.negotiate(remote);

		assertFalse(result.compatible());
		assertTrue(result.reason().contains("MAJOR"));
	}

	@Test
	void parseRoundTrip() {
		assertEquals(new ProtocolVersion(1, 2, 3), ProtocolVersion.parse("1.2.3"));
		assertEquals("1.2.3", new ProtocolVersion(1, 2, 3).toString());
	}

	@Test
	void parseRejectsMalformedInput() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> ProtocolVersion.parse("1.2"));
	}
}
