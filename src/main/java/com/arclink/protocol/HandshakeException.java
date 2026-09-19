package com.arclink.protocol;

/** Thrown when auth or version negotiation fails; {@link #getMessage()} is user-facing. */
public final class HandshakeException extends Exception {
	public HandshakeException(String message) {
		super(message);
	}
}
