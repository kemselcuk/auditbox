package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Plain unit tests for the canonical content hash (plan task T2.2): no Spring context, no
 * containers. Each documented canonicalization rule is pinned by a test.
 */
class EventContentHasherTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	// Fixed content baseline shared by the tests; variants flip exactly one field.
	private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";
	private static final String USER_ID = "user-42";
	private static final String EVENT_TYPE = "payment.settled";
	private static final String SOURCE = "core-banking";
	private static final String OCCURRED_AT = "2026-10-18T12:00:00Z";

	private static JsonNode metadata(String json) {
		// readTree preserves insertion order, so shuffled keys really arrive shuffled.
		return JSON.readTree(json);
	}

	@Test
	void metadataKeyOrderDoesNotChangeHash() {
		JsonNode sortedForm = metadata(
				"{\"amount\":100,\"currency\":\"EUR\",\"details\":{\"z\":1,\"a\":{\"y\":2,\"x\":3}}}");
		JsonNode shuffledForm = metadata(
				"{\"details\":{\"a\":{\"x\":3,\"y\":2},\"z\":1},\"currency\":\"EUR\",\"amount\":100}");

		String sorted = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, sortedForm);
		String shuffled = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, shuffledForm);

		assertThat(shuffled).isEqualTo(sorted);
	}

	@Test
	void canonicalFormHasSortedKeysAtEveryLevel() {
		// Locks the exact canonical JSON: content-field keys and metadata keys, all sorted.
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"details\":{\"z\":1,\"a\":{\"y\":2,\"x\":3}},\"currency\":\"EUR\",\"amount\":100}"));

		assertThat(canonical).isEqualTo("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + EVENT_TYPE
				+ "\",\"metadata\":{\"amount\":100,\"currency\":\"EUR\",\"details\":{\"a\":{\"x\":3,\"y\":2},\"z\":1}},"
				+ "\"occurredAt\":\"" + OCCURRED_AT + "\",\"source\":\"" + SOURCE + "\",\"userId\":\"" + USER_ID
				+ "\"}");
	}

	@Test
	void sameInstantWithDifferentOffsetsHashesIdentically() {
		String utc = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00Z", null);
		String plusTwo = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T14:00:00+02:00",
				null);
		String minusTwelve = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE,
				"2026-10-18T00:00:00-12:00", null);
		String zeroFraction = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.000Z",
				null);

		assertThat(plusTwo).isEqualTo(utc);
		assertThat(minusTwelve).isEqualTo(utc);
		assertThat(zeroFraction).isEqualTo(utc);
	}

	@Test
	void changingEachContentFieldOnceChangesTheHash() {
		String base = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":100}"));

		assertThat(EventContentHasher.hash("99999999-2222-3333-4444-555555555555", USER_ID, EVENT_TYPE, SOURCE,
				OCCURRED_AT, metadata("{\"amount\":100}"))).isNotEqualTo(base);
		assertThat(EventContentHasher.hash(EVENT_ID, "user-43", EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":100}"))).isNotEqualTo(base);
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, "payment.refunded", SOURCE, OCCURRED_AT,
				metadata("{\"amount\":100}"))).isNotEqualTo(base);
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, "card-gateway", OCCURRED_AT,
				metadata("{\"amount\":100}"))).isNotEqualTo(base);
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:01Z",
				metadata("{\"amount\":100}"))).isNotEqualTo(base);
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":101}"))).isNotEqualTo(base);
	}

	@Test
	void absentAndNullMetadataFollowTheDocumentedRule() {
		// Documented rule: absent and explicit null are the same content.
		String absent = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, null);

		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, NullNode.getInstance()))
				.isEqualTo(absent);
		// Any present metadata — even an empty object — is different content.
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, metadata("{}")))
				.isNotEqualTo(absent);
	}

	@Test
	void numbersCanonicalizeAsExactDecimals() {
		// Documented rule: 1, 1.0 and 1.00 are the same number content.
		String integer = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":1}"));
		String oneDecimal = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":1.0}"));
		String twoDecimals = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"amount\":1.00}"));

		assertThat(oneDecimal).isEqualTo(integer);
		assertThat(twoDecimals).isEqualTo(integer);
	}

	@Test
	void hashIsLowercaseHexSha256OverTheCanonicalJson() throws Exception {
		String hash = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, null);

		assertThat(hash).matches("[0-9a-f]{64}");

		// Independent recomputation: hash() must be SHA-256 of the documented canonical JSON.
		String canonicalJson = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				null);
		assertThat(canonicalJson).isEqualTo("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + EVENT_TYPE
				+ "\",\"metadata\":null,\"occurredAt\":\"" + OCCURRED_AT + "\",\"source\":\"" + SOURCE
				+ "\",\"userId\":\"" + USER_ID + "\"}");
		String independentlyComputed = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));

		assertThat(hash).isEqualTo(independentlyComputed);
	}

	@Test
	void hashUsesUtf8BytesForNonAsciiContent() {
		String ascii = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"name\":\"cafe\"}"));
		String nonAscii = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"name\":\"café\"}"));

		assertThat(nonAscii).isNotEqualTo(ascii).matches("[0-9a-f]{64}");
	}
}
