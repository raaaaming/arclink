package com.arclink.protocol;

/**
 * Semantic version for the ARCLink wire protocol (CLAUDE.md 3.3).
 * Golden rule: MAJOR mismatch => reject, otherwise degrade to the lower MINOR.
 */
public record ProtocolVersion(int major, int minor, int patch) {

	public static ProtocolVersion parse(String text) {
		String[] parts = text.trim().split("\\.");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Not a MAJOR.MINOR.PATCH version: " + text);
		}
		try {
			return new ProtocolVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a MAJOR.MINOR.PATCH version: " + text, e);
		}
	}

	public Negotiation negotiate(ProtocolVersion other) {
		if (this.major != other.major) {
			return new Negotiation(false, 0,
					"Incompatible ARCLink protocol: local v" + this + " vs remote v" + other
							+ " — MAJOR version differs, update the mod or the server plugin.");
		}
		return new Negotiation(true, Math.min(this.minor, other.minor), null);
	}

	@Override
	public String toString() {
		return major + "." + minor + "." + patch;
	}

	/** Result of comparing two {@link ProtocolVersion}s. {@code reason} is user-facing when incompatible. */
	public record Negotiation(boolean compatible, int negotiatedMinor, String reason) {
	}
}
