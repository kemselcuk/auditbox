package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the constant-time API key comparison (T5.1): null or blank values on
 * either side never match; equal values match; anything else does not.
 */
class ApiKeyMatcherTest {

	@Test
	void equalKeysMatch() {
		assertThat(ApiKeyMatcher.matches("local-dev-key", "local-dev-key")).isTrue();
		assertThat(ApiKeyMatcher.matches("test-api-key", "test-api-key")).isTrue();
	}

	@Test
	void differentKeysDoNotMatch() {
		assertThat(ApiKeyMatcher.matches("local-dev-key", "wrong-value")).isFalse();
		assertThat(ApiKeyMatcher.matches("key-with-suffix", "key-with-suffi")).isFalse();
	}

	@Test
	void nullOrBlankConfiguredKeyNeverMatches() {
		assertThat(ApiKeyMatcher.matches(null, "anything")).isFalse();
		assertThat(ApiKeyMatcher.matches("", "anything")).isFalse();
		assertThat(ApiKeyMatcher.matches("   ", "anything")).isFalse();
	}

	@Test
	void nullOrBlankProvidedKeyNeverMatches() {
		assertThat(ApiKeyMatcher.matches("local-dev-key", null)).isFalse();
		assertThat(ApiKeyMatcher.matches("local-dev-key", "")).isFalse();
		assertThat(ApiKeyMatcher.matches("local-dev-key", "  ")).isFalse();
	}

	/**
	 * Review round 4, F3: surrounding whitespace on the configured side is stripped before
	 * comparison (the servlet container trims header names' handling such that an untrimmed
	 * configured key could otherwise never match); the provided header value is compared
	 * exactly as received.
	 */
	@Test
	void configuredKeyWithSurroundingWhitespaceMatchesTrimmedProvidedValue() {
		assertThat(ApiKeyMatcher.matches(" key ", "key")).isTrue();
		assertThat(ApiKeyMatcher.matches("\tkey\n", "key")).isTrue();
		// The provided side is deliberately not stripped: only the configuration side may
		// carry operator-convenience whitespace.
		assertThat(ApiKeyMatcher.matches("key", " key ")).isFalse();
	}

	@Test
	void blankOnBothSidesStillNeverMatches() {
		assertThat(ApiKeyMatcher.matches(null, null)).isFalse();
		assertThat(ApiKeyMatcher.matches("", "")).isFalse();
	}
}
