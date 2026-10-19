package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-source API-key registry (milestone 10.1, decision D18): parsing
 * and validation of {@code source:sha256hex} entries, rotation overlaps (the same source
 * listed more than once), whitespace and case normalization, deny-all for blank values,
 * and source resolution for {@code authenticate()}. Malformed values fail with a message
 * naming the entry index and the reason — and never the digest (or any other key
 * material). The digest helper is shared with the profile defaults, so the externally
 * documented digest constants double as fixtures.
 */
class ApiKeyRegistryTest {

	/** Digest of the documented local development key (application-local.yml). */
	private static final String LOCAL_DEV_HASH = "ed5a18fb8f807f996d649e379d3f35f39c543a91bdbf88c492f2ebd10d4df86c";

	/** Digest of the documented test key (application-test.yml). */
	private static final String TEST_HASH = "4c806362b613f7496abf284146efd31da90e4b16169fe001841ca17290f427c4";

	private static String sha256Hex(String value) {
		return HexFormat.of().formatHex(ApiKeyMatcher.sha256(value));
	}

	private static ApiKeyRegistry registryOf(String raw) {
		return new ApiKeyRegistry(new ApiKeyProperties(raw));
	}

	// ---------------------------------------------------------------------
	// Parsing and matching
	// ---------------------------------------------------------------------

	@Test
	void singleEntryAuthenticatesItsKeyToItsSource() {
		ApiKeyRegistry registry = registryOf("core-banking:" + sha256Hex("key-one"));

		assertThat(registry.authenticate("key-one")).contains("core-banking");
		assertThat(registry.authenticate("key-two")).isEmpty();
	}

	@Test
	void multipleEntriesAuthenticateEachKeyToItsOwnSource() {
		ApiKeyRegistry registry = registryOf("core-banking:" + LOCAL_DEV_HASH + ",partner-x:" + TEST_HASH);

		assertThat(registry.authenticate("local-dev-key")).contains("core-banking");
		assertThat(registry.authenticate("test-api-key")).contains("partner-x");
	}

	/**
	 * Rotation overlap (D18): the same source listed twice means two independently valid
	 * keys for that source — the new key works while the old one is still distributed.
	 */
	@Test
	void duplicateSourceOpensARotationOverlapWindowWhereBothKeysWork() {
		ApiKeyRegistry registry = registryOf(
				"core-banking:" + sha256Hex("old-key") + ",core-banking:" + sha256Hex("new-key"));

		assertThat(registry.authenticate("old-key")).contains("core-banking");
		assertThat(registry.authenticate("new-key")).contains("core-banking");
	}

	@Test
	void surroundingWhitespaceIsTrimmedFromEntriesAndParts() {
		ApiKeyRegistry registry = registryOf("  core-banking : " + LOCAL_DEV_HASH + "  ,  partner-x:"
				+ TEST_HASH + "  ");

		assertThat(registry.authenticate("local-dev-key")).contains("core-banking");
		assertThat(registry.authenticate("test-api-key")).contains("partner-x");
	}

	@Test
	void upperCaseDigestsAreAcceptedAndNormalizeToLowercase() {
		String upper = sha256Hex("local-dev-key").toUpperCase();

		assertThat(registryOf("core-banking:" + upper).authenticate("local-dev-key"))
				.contains("core-banking");
	}

	@Test
	void twoEntriesSharingADigestAuthenticateAsTheFirstListedSource() {
		ApiKeyRegistry registry = registryOf("first:" + sha256Hex("shared") + ",second:" + sha256Hex("shared"));

		assertThat(registry.authenticate("shared")).contains("first");
	}

	// ---------------------------------------------------------------------
	// Deny-all: blank/absent configuration and blank/absent header values
	// ---------------------------------------------------------------------

	@Test
	void blankOrAbsentValueYieldsAnEmptyRegistryThatAuthenticatesNothing() {
		for (String raw : new String[] { null, "", "   ", "\t\n" }) {
			ApiKeyRegistry registry = registryOf(raw);

			assertThat(registry.authenticate("local-dev-key")).isEmpty();
		}
	}

	@Test
	void nullOrBlankProvidedValuesNeverMatchEvenWithEntries() {
		ApiKeyRegistry registry = registryOf("core-banking:" + LOCAL_DEV_HASH);

		assertThat(registry.authenticate(null)).isEmpty();
		assertThat(registry.authenticate("")).isEmpty();
		assertThat(registry.authenticate("   ")).isEmpty();
	}

	// ---------------------------------------------------------------------
	// Malformed values fail fast, naming index and reason, never the digest
	// ---------------------------------------------------------------------

	@Test
	void entryWithoutColonFailsNamingTheIndexWithoutEchoingTheValue() {
		String digest = sha256Hex("local-dev-key");

		assertThatThrownBy(() -> registryOf("core-banking:" + digest + ",justatoken"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index 1")
				.hasMessageContaining("auditbox.security.api-keys")
				// The offending value is never echoed.
				.hasMessageNotContaining("justatoken")
				.hasMessageNotContaining(digest);
	}

	@Test
	void sourceOutsideTheAllowedCharacterSetFailsNamingTheIndex() {
		String digest = sha256Hex("local-dev-key");

		assertThatThrownBy(() -> registryOf("core-banking:" + digest + ",bad@source:" + digest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index 1")
				.hasMessageContaining("source")
				.hasMessageNotContaining("bad@source")
				.hasMessageNotContaining(digest);
	}

	@Test
	void sourceLongerThanSixtyFourCharactersFailsNamingTheIndex() {
		String tooLong = "s".repeat(65);

		assertThatThrownBy(() -> registryOf(tooLong + ":" + sha256Hex("local-dev-key")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index 0")
				.hasMessageContaining("source")
				.hasMessageNotContaining(tooLong);
	}

	@Test
	void digestWithTheWrongLengthFailsNamingTheIndexWithoutEchoingTheDigest() {
		String shortDigest = sha256Hex("local-dev-key").substring(0, 63);

		assertThatThrownBy(() -> registryOf("core-banking:" + shortDigest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index 0")
				.hasMessageContaining("digest")
				.hasMessageNotContaining(shortDigest);
	}

	@Test
	void nonHexDigestFailsNamingTheIndexWithoutEchoingTheDigest() {
		String nonHex = "ghijklmnopqrstuvwxyz0123456789ghijklmnopqrstuvwxyz0123456789gh";

		assertThatThrownBy(() -> registryOf("core-banking:" + nonHex))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index 0")
				.hasMessageContaining("digest")
				.hasMessageNotContaining(nonHex);
	}

	@Test
	void theDocumentedDigestsStayPinnedToTheirPlaintextKeys() {
		// Guards against accidental edits of the documented digest constants: the
		// application-test.yml value must stay the SHA-256 of the plaintext test-api-key.
		assertThat(TEST_HASH).isEqualTo(sha256Hex("test-api-key"));
		assertThat(LOCAL_DEV_HASH).isEqualTo(sha256Hex("local-dev-key"));
	}
}
