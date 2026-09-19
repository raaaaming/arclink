package com.arclink.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCodecTest {

	@Test
	void validSignatureVerifies() {
		byte[] secret = AuthCodec.randomNonce(32);
		byte[] nonce = "challenge-nonce".getBytes(StandardCharsets.UTF_8);

		String signature = AuthCodec.sign(secret, nonce);

		assertTrue(AuthCodec.verify(secret, nonce, signature));
	}

	@Test
	void tamperedSignatureIsRejected() {
		byte[] secret = AuthCodec.randomNonce(32);
		byte[] nonce = "challenge-nonce".getBytes(StandardCharsets.UTF_8);
		String signature = AuthCodec.sign(secret, nonce);
		String tampered = signature.equals("AAAA") ? "BBBB" : "AAAA" + signature.substring(4);

		assertFalse(AuthCodec.verify(secret, nonce, tampered));
	}

	@Test
	void wrongSecretIsRejected() {
		byte[] secret = AuthCodec.randomNonce(32);
		byte[] otherSecret = AuthCodec.randomNonce(32);
		byte[] nonce = "challenge-nonce".getBytes(StandardCharsets.UTF_8);

		String signature = AuthCodec.sign(secret, nonce);

		assertFalse(AuthCodec.verify(otherSecret, nonce, signature));
	}

	@Test
	void malformedBase64SignatureIsRejectedNotThrown() {
		byte[] secret = AuthCodec.randomNonce(32);
		byte[] nonce = "n".getBytes(StandardCharsets.UTF_8);

		assertFalse(AuthCodec.verify(secret, nonce, "not-base64!!"));
	}
}
