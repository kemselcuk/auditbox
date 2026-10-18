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

	@Test
	void blankOnBothSidesStillNeverMatches() {
		assertThat(ApiKeyMatcher.matches(null, null)).isFalse();
		assertThat(ApiKeyMatcher.matches("", "")).isFalse();
	}
}
