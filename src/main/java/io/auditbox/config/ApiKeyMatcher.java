package io.auditbox.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Constant-time digest helper for the per-source API-key registry (milestone 10.1,
 * decision D18; successor of the D5 matcher): the configured side is stored as SHA-256
 * digests, so the provided {@code X-API-Key} value is reduced to its SHA-256 digest once
 * and the registry compares it against every stored digest with
 * {@link MessageDigest#isEqual}, which runs over all bytes regardless of where a mismatch
 * occurs (the registry's loop additionally never exits early, so even the number of
 * comparisons is constant). Null and blank provided values never match — in particular a
 * blank configured value (no {@code API_KEYS}) yields an empty registry that
 * authenticates nothing. No value is ever logged.
 */
final class ApiKeyMatcher {

	private ApiKeyMatcher() {
	}

	/**
	 * @param value any string, e.g. the raw {@code X-API-Key} header value
	 * @return the SHA-256 digest of the UTF-8 bytes of the value
	 */
	static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			// Every platform Java (JDK 21+) ships SHA-256; this cannot happen on a supported JDK.
			throw new IllegalStateException("SHA-256 MessageDigest is not available", ex);
		}
	}
}
