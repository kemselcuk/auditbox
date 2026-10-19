package io.auditbox.config;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import java.security.MessageDigest;

/**
 * Registry of per-source API keys (milestone 10.1, decision D18): replaces the single
 * static plaintext key of D5 with SHA-256-hashed entries read from
 * {@code auditbox.security.api-keys} (bound from the {@code API_KEYS} environment
 * variable). The value is a comma-separated list of {@code source:sha256hex} entries:
 *
 * <ul>
 *   <li>{@code source}: 1..64 characters of {@code [A-Za-z0-9._-]} — the name a matching
 *       request authenticates as,</li>
 *   <li>{@code sha256hex}: exactly 64 hexadecimal characters — the SHA-256 digest of the
 *       plaintext key ({@code printf %s 'the-key' | sha256sum}); upper-case input is
 *       accepted and normalized to lower case internally,</li>
 *   <li>entries and their parts are trimmed of surrounding whitespace; empty entries
 *       (stray commas) are ignored,</li>
 *   <li>the same source may appear more than once: each entry is an independently valid
 *       key, which is how a rotation overlap window works — removing the old entry ends
 *       the window,</li>
 *   <li>a blank or absent value yields an EMPTY registry: nothing authenticates (the same
 *       deny-all behavior the blank plaintext key had),</li>
 *   <li>a malformed entry (missing colon, bad source, wrong-length or non-hex digest)
 *       fails startup with an {@link IllegalStateException} that names the entry index and
 *       the reason but NEVER echoes the digest or any other key material.</li>
 * </ul>
 *
 * <p>The entries are parsed and validated once at construction; only the source names and
 * their digests are kept — never a plaintext key. Authentication is constant time and
 * position-agnostic: the provided value is SHA-256-hashed once, then the digest is
 * compared with {@link MessageDigest#isEqual} against EVERY stored digest (the loop never
 * exits early, so request timing does not reveal the matched position), and the first
 * matching entry's source is returned. Null and blank provided values never match. No
 * value — provided, configured, or digested — is ever logged.
 */
class ApiKeyRegistry {

	/** Source names: 1..64 characters of [A-Za-z0-9._-]. */
	private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	/** Exactly 64 hexadecimal characters; case-insensitive on input, normalized to lower case. */
	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	/** One registered key: the source name and the SHA-256 digest of its plaintext key. */
	private record Entry(String source, byte[] digest) {
	}

	private final List<Entry> entries;

	ApiKeyRegistry(ApiKeyProperties properties) {
		this.entries = List.copyOf(parse(properties.apiKeys()));
	}

	/**
	 * @param providedHeader the raw {@code X-API-Key} header value (may be null/blank)
	 * @return the source name of the first matching entry, or empty when nothing matches
	 */
	Optional<String> authenticate(String providedHeader) {
		if (providedHeader == null || providedHeader.isBlank() || entries.isEmpty()) {
			return Optional.empty();
		}
		byte[] providedDigest = ApiKeyMatcher.sha256(providedHeader);
		String firstMatch = null;
		// No early exit: every stored digest is compared on every request so the request
		// time does not reveal how far into the list the matching entry sits.
		for (Entry entry : entries) {
			boolean matches = MessageDigest.isEqual(entry.digest(), providedDigest);
			if (matches && firstMatch == null) {
				firstMatch = entry.source();
			}
		}
		return Optional.ofNullable(firstMatch);
	}

	/**
	 * Parses and validates the raw configuration value. Package-private and reusable so
	 * {@link ApiKeyProdRequirement} can apply the same validation for its prod fail-fast
	 * checks (the registry constructor runs the identical code path in every profile).
	 *
	 * @param rawValue the raw {@code auditbox.security.api-keys} value (may be null/blank)
	 * @return the parsed entries in configuration order; empty for a null/blank value or a
	 *         value consisting only of empty entries
	 * @throws IllegalStateException naming the entry index and the reason on the first
	 *         malformed entry; never echoes the digest or any key material
	 */
	static List<Entry> parse(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return List.of();
		}
		String[] rawEntries = rawValue.split(",");
		List<Entry> entries = new ArrayList<>(rawEntries.length);
		for (int index = 0; index < rawEntries.length; index++) {
			String entry = rawEntries[index].strip();
			if (entry.isEmpty()) {
				continue; // stray comma between entries; a value of only commas parses to zero entries
			}
			int separator = entry.indexOf(':');
			if (separator < 0) {
				throw malformed(index, "the ':' separator between source and digest is missing; expected 'source:sha256hex'");
			}
			String source = entry.substring(0, separator).strip();
			String digestHex = entry.substring(separator + 1).strip().toLowerCase(Locale.ROOT);
			if (!SOURCE.matcher(source).matches()) {
				throw malformed(index, "the source must be 1..64 characters of [A-Za-z0-9._-]");
			}
			if (!SHA256_HEX.matcher(digestHex).matches()) {
				throw malformed(index,
						"the digest must be exactly 64 hexadecimal characters (the sha256sum of the plaintext key)");
			}
			entries.add(new Entry(source, HexFormat.of().parseHex(digestHex)));
		}
		return List.copyOf(entries);
	}

	private static IllegalStateException malformed(int index, String reason) {
		// The message names the entry index and the reason only: no entry value, digest,
		// or other key material is ever echoed.
		return new IllegalStateException(
				"Invalid auditbox.security.api-keys entry at index " + index + ": " + reason);
	}
}
