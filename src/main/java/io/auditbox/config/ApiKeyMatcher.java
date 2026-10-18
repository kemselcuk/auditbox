package io.auditbox.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Constant-time comparison of the configured API key against the provided header value
 * (decision D5): both values are reduced to SHA-256 digests and the digests are compared
 * with {@link MessageDigest#isEqual}, which runs over all bytes regardless of where a
 * mismatch occurs (except length, which leaks nothing useful for fixed-length digests).
 *
 * <p>Null and blank values never match — in particular a blank configured key (no
 * {@code API_KEY}) authenticates nothing, so a profile-less run denies everything except
 * the public actuator health endpoints. The configured key is stripped of surrounding
 * whitespace before comparison (review round 4, F3 — headers are trimmed by the container,
 * so {@code " key "} in the configuration could otherwise never match); the provided
 * header value is compared exactly as received. No value is ever logged.
 */
final class ApiKeyMatcher {

	private ApiKeyMatcher() {
	}

	/**
	 * @param configured the configured key (may be null/blank or carry surrounding
	 *                   whitespace — then nothing matches after stripping)
	 * @param provided the raw {@code X-API-Key} header value (may be null/blank)
	 */
	static boolean matches(String configured, String provided) {
		String trimmedConfigured = configured == null ? null : configured.strip();
		if (trimmedConfigured == null || trimmedConfigured.isEmpty() || provided == null || provided.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(sha256(trimmedConfigured), sha256(provided));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			// Every platform Java (JDK 21+) ships SHA-256; this cannot happen on a supported JDK.
			throw new IllegalStateException("SHA-256 MessageDigest is not available", ex);
		}
	}
}
