package com.arclink.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * HMAC-SHA256 challenge/response primitives for the handshake (CLAUDE.md 3.2).
 * Signature comparison is constant-time to resist timing attacks; the shared
 * secret itself is never put on the wire, only nonces and signatures are.
 */
public final class AuthCodec {
	private static final String HMAC_ALGO = "HmacSHA256";
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private AuthCodec() {
	}

	public static byte[] randomNonce(int length) {
		byte[] bytes = new byte[length];
		SECURE_RANDOM.nextBytes(bytes);
		return bytes;
	}

	public static byte[] hmac(byte[] secret, byte[] message) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(secret, HMAC_ALGO));
			return mac.doFinal(message);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	public static String sign(byte[] secret, byte[] message) {
		return Base64.getEncoder().encodeToString(hmac(secret, message));
	}

	/** Constant-time verification: never short-circuits on the first differing byte. */
	public static boolean verify(byte[] secret, byte[] message, String signatureBase64) {
		byte[] expected = hmac(secret, message);
		byte[] actual;
		try {
			actual = Base64.getDecoder().decode(signatureBase64);
		} catch (IllegalArgumentException e) {
			return false;
		}
		return MessageDigest.isEqual(expected, actual);
	}
}
