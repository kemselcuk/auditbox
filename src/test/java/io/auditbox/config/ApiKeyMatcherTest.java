package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the digest helper used by the per-source API-key registry (milestone
 * 10.1, decision D18; formerly the D5 matcher's comparison, which moved into
 * {@link ApiKeyRegistry}): the helper produces deterministic SHA-256 digests over exact
 * UTF-8 bytes, equal to the externally documented digest constants used in the profile
 * defaults and to an independent {@code sha256sum} computation. The constant-time
 * comparison itself is pinned by {@link ApiKeyRegistryTest}.
 */
class ApiKeyMatcherTest {

	@Test
	void producesTheDocumentedSha256Digests() {
		assertThat(HexFormat.of().formatHex(ApiKeyMatcher.sha256("local-dev-key")))
				.isEqualTo("ed5a18fb8f807f996d649e379d3f35f39c543a91bdbf88c492f2ebd10d4df86c");
		assertThat(HexFormat.of().formatHex(ApiKeyMatcher.sha256("test-api-key")))
				.isEqualTo("4c806362b613f7496abf284146efd31da90e4b16169fe001841ca17290f427c4");
	}

	@Test
	void digestIsDeterministic() {
		byte[] first = ApiKeyMatcher.sha256("any-value");
		byte[] second = ApiKeyMatcher.sha256("any-value");

		assertThat(second).isEqualTo(first);
	}

	@Test
	void differentInputsProduceDifferentDigests() {
		assertThat(ApiKeyMatcher.sha256("key-one")).isNotEqualTo(ApiKeyMatcher.sha256("key-two"));
		assertThat(ApiKeyMatcher.sha256("key-with-suffix")).isNotEqualTo(ApiKeyMatcher.sha256("key-with-suffi"));
	}

	/**
	 * The digest covers the exact value including surrounding whitespace: a key
	 * configured with whitespace would produce a different digest than the same key
	 * trimmed (the registry strips entry parts before hashing the configured side, while
	 * the provided header value is hashed exactly as received).
	 */
	@Test
	void digestCoversTheExactUtf8BytesIncludingWhitespace() {
		assertThat(ApiKeyMatcher.sha256(" key ")).isNotEqualTo(ApiKeyMatcher.sha256("key"));
		byte[] digested = ApiKeyMatcher.sha256("ключ-𝕌");
		assertThat(digested).hasSize(32);
	}

	@Test
	void digestsAreAlwaysThirtyTwoBytes() {
		assertThat(ApiKeyMatcher.sha256("")).hasSize(32);
		assertThat(ApiKeyMatcher.sha256("a-very-long-value".repeat(100))).hasSize(32);
	}
}
