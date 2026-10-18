package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Plain unit tests for the canonical content hash (plan task T2.2): no Spring context, no
 * containers. Each documented canonicalization rule is pinned by a test. Number
 * canonicalization is parameterized over the two mapper configurations that matter: the
 * default mapper (DoubleNode path) and the mapper Stage 3 must use to read request JSON
 * ({@code USE_BIG_DECIMAL_FOR_FLOATS}, DecimalNode path).
 */
class EventContentHasherTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The mapper configuration production must use (exact decimals for floats). */
	private static final JsonMapper BIG_DECIMAL_JSON = JsonMapper.builder()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();

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

	private static String hashWith(JsonMapper mapper, String metadataJson) {
		return EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				mapper.readTree(metadataJson));
	}

	/**
	 * The two mapper configurations that matter for number canonicalization: the default
	 * (DoubleNode path) and the caller requirement documented on
	 * {@link EventContentHasher} ({@code USE_BIG_DECIMAL_FOR_FLOATS}, DecimalNode path).
	 */
	private static Stream<Arguments> numberMappers() {
		return Stream.of(
				Arguments.of("default mapper (DoubleNode path)", JSON),
				Arguments.of("BigDecimal mapper (DecimalNode path)", BIG_DECIMAL_JSON));
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
	void occurredAtFractionsHashAtMicrosecondPrecision() {
		String micros = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.123456Z",
				null);
		String nanos = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.123456789Z",
				null);

		// A retry that echoes the occurredAt value the API returned must not false-conflict.
		assertThat(nanos).isEqualTo(micros);

		// The canonical string itself shows the truncation, not just coinciding hashes.
		assertThat(EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE,
				"2026-10-18T12:00:00.123456789Z", null))
				.contains("\"occurredAt\":\"2026-10-18T12:00:00.123456Z\"");
	}

	@Test
	void subMicrosecondFractionsTruncateDownToMicroseconds() {
		String floor = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.1234561Z",
				null);

		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.1234564Z",
				null)).isEqualTo(floor);
		assertThat(EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE,
				"2026-10-18T12:00:00.1234564Z", null)).contains("\"occurredAt\":\"2026-10-18T12:00:00.123456Z\"");
		// The next microsecond is genuinely different content.
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, "2026-10-18T12:00:00.123457Z",
				null)).isNotEqualTo(floor);
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
	void nullContentFieldsFailFastWithNullPointerException() {
		// Documented rule: null content fields fail fast; metadata may be null by design.
		assertThatNullPointerException()
				.isThrownBy(() -> EventContentHasher.hash(null, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, null));
		assertThatNullPointerException()
				.isThrownBy(() -> EventContentHasher.hash(EVENT_ID, null, EVENT_TYPE, SOURCE, OCCURRED_AT, null));
		assertThatNullPointerException()
				.isThrownBy(() -> EventContentHasher.hash(EVENT_ID, USER_ID, null, SOURCE, OCCURRED_AT, null));
		assertThatNullPointerException()
				.isThrownBy(() -> EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, null, OCCURRED_AT, null));
		assertThatNullPointerException()
				.isThrownBy(() -> EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, null, null));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("numberMappers")
	void equivalentNumberFormsHashIdentically(String label, JsonMapper mapper) {
		// Documented rule: 1, 1.0 and 1.00 are the same number content.
		assertThat(hashWith(mapper, "{\"amount\":1.0}")).isEqualTo(hashWith(mapper, "{\"amount\":1}"));
		assertThat(hashWith(mapper, "{\"amount\":1.00}")).isEqualTo(hashWith(mapper, "{\"amount\":1}"));
		// Exponent forms equal their plain decimals: 1e3 == 1000 and 0.1 == 1e-1.
		assertThat(hashWith(mapper, "{\"amount\":1e3}")).isEqualTo(hashWith(mapper, "{\"amount\":1000}"));
		assertThat(hashWith(mapper, "{\"amount\":0.1}")).isEqualTo(hashWith(mapper, "{\"amount\":1e-1}"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("numberMappers")
	void exponentFormsAreWrittenAsPlainDecimals(String label, JsonMapper mapper) {
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				mapper.readTree("{\"amount\":1e3,\"rate\":1e-1}"));

		assertThat(canonical).contains("\"amount\":1000").contains("\"rate\":0.1");
	}

	@Test
	void exponentLiteralViaBigDecimalNodeWritesPlainDecimal() {
		// 1E+3 read as a DecimalNode must canonicalize to the plain decimal 1000.
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				BIG_DECIMAL_JSON.readTree("{\"amount\":1E+3}"));

		assertThat(canonical).contains("\"amount\":1000").doesNotContain("E+");
	}

	@Test
	void highPrecisionDecimalIsDistinctFromOneOnTheBigDecimalPath() {
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				BIG_DECIMAL_JSON.readTree("{\"amount\":1.00000000000000000001}"));

		// The caller requirement (USE_BIG_DECIMAL_FOR_FLOATS) keeps the digits exact.
		assertThat(canonical).contains("\"amount\":1.00000000000000000001");
		assertThat(hashWith(BIG_DECIMAL_JSON, "{\"amount\":1.00000000000000000001}"))
				.isNotEqualTo(hashWith(BIG_DECIMAL_JSON, "{\"amount\":1}"));
	}

	@Test
	void highPrecisionDecimalFollowsTheDocumentedDoubleNodeLimit() {
		// Documented known limit (D10): on the default DoubleNode path the value collapses to
		// the shortest round-trip double (1.0), i.e. it canonicalizes equal to 1. This is
		// exactly why the caller must read metadata with USE_BIG_DECIMAL_FOR_FLOATS.
		assertThat(hashWith(JSON, "{\"amount\":1.00000000000000000001}")).isEqualTo(hashWith(JSON, "{\"amount\":1}"));
	}

	@Test
	void outOfRangeExponentCanonicalizesToPlainDecimalOnTheBigDecimalPath() {
		// 1e400 overflows double; the BigDecimal path must canonicalize it without exception.
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				BIG_DECIMAL_JSON.readTree("{\"amount\":1e400}"));

		assertThat(canonical).contains("\"amount\":1" + "0".repeat(400));
		assertThat(EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				BIG_DECIMAL_JSON.readTree("{\"amount\":1e400}"))).matches("[0-9a-f]{64}");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("numberMappers")
	void integerBeyondLongRangeHashesExactly(String label, JsonMapper mapper) {
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				mapper.readTree("{\"amount\":123456789012345678901234567890}"));

		// No truncation to double: the exact digits survive into the canonical form.
		assertThat(canonical).contains("\"amount\":123456789012345678901234567890");
		assertThat(hashWith(mapper, "{\"amount\":123456789012345678901234567890}"))
				.matches("[0-9a-f]{64}")
				.isNotEqualTo(hashWith(mapper, "{\"amount\":123456789012345678901234567891}"));
	}

	@Test
	void arraysPreserveOrderAndNestedObjectsStillSortKeys() {
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("[{\"b\":1,\"a\":2},{\"d\":4,\"c\":3}]"));

		// Nested object keys are sorted; array element order is preserved verbatim.
		assertThat(canonical).contains("\"metadata\":[{\"a\":2,\"b\":1},{\"c\":3,\"d\":4}]");

		// Swapping array elements is different content (element order is significant).
		assertThat(hashWith(JSON, "[{\"d\":4,\"c\":3},{\"b\":1,\"a\":2}]"))
				.isNotEqualTo(hashWith(JSON, "[{\"b\":1,\"a\":2},{\"d\":4,\"c\":3}]"));
	}

	@Test
	void booleansAndNullsInsideMetadataAreCanonicalized() {
		String canonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"note\":null,\"active\":true,\"deleted\":false}"));

		assertThat(canonical).contains("\"metadata\":{\"active\":true,\"deleted\":false,\"note\":null}");
		assertThat(hashWith(JSON, "{\"active\":true,\"deleted\":false,\"note\":null}"))
				.isNotEqualTo(hashWith(JSON, "{\"active\":false,\"deleted\":false,\"note\":null}"));
	}

	@Test
	void nonObjectMetadataHashesDeterministically() {
		// The hasher treats any JSON value as metadata; canonicalization must stay
		// deterministic for non-object roots too.
		String arrayCanonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("[1,{\"b\":1,\"a\":2},3]"));
		String stringCanonical = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("\"payload\""));

		assertThat(arrayCanonical).contains("\"metadata\":[1,{\"a\":2,\"b\":1},3]");
		assertThat(stringCanonical).contains("\"metadata\":\"payload\"");
		assertThat(hashWith(JSON, "[1,{\"b\":1,\"a\":2},3]"))
				.isEqualTo(hashWith(JSON, "[1,{\"a\":2,\"b\":1},3]"))
				.isNotEqualTo(hashWith(JSON, "\"payload\""));
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
	void hashUsesUtf8BytesForNonAsciiContent() throws Exception {
		String ascii = hashWith(JSON, "{\"name\":\"cafe\"}");
		String nonAscii = hashWith(JSON, "{\"name\":\"café\"}");

		assertThat(nonAscii).isNotEqualTo(ascii).matches("[0-9a-f]{64}");

		// Independent recomputation over the documented UTF-8 bytes of the canonical JSON.
		// The canonical text itself carries the é, so this only matches if the digest was
		// taken over UTF-8 bytes: an ISO-8859-1 implementation encodes the same character as
		// a single byte and produces a different digest.
		String canonicalJson = EventContentHasher.canonicalJson(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT,
				metadata("{\"name\":\"café\"}"));
		assertThat(canonicalJson).contains("café");
		String utf8Digest = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
		String iso88591Digest = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.ISO_8859_1)));

		// Sanity: the two encodings really differ, so this check can discriminate.
		assertThat(iso88591Digest).isNotEqualTo(utf8Digest);
		assertThat(nonAscii).isEqualTo(utf8Digest).isNotEqualTo(iso88591Digest);
	}

	@Test
	void concurrentHashingMatchesSingleThreadedHash() throws Exception {
		// The hasher claims to be safe for concurrent use over shared, immutable trees.
		JsonNode metadata = metadata("{\"amount\":99,\"tags\":[\"card\",\"sepa\"],\"ok\":true}");
		String expected = EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, metadata);

		int threads = 8;
		int iterations = 200;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<String>> tasks = new ArrayList<>();
			for (int i = 0; i < iterations; i++) {
				tasks.add(() -> EventContentHasher.hash(EVENT_ID, USER_ID, EVENT_TYPE, SOURCE, OCCURRED_AT, metadata));
			}
			List<Future<String>> futures = executor.invokeAll(tasks);
			for (Future<String> future : futures) {
				assertThat(future.get()).isEqualTo(expected);
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

}
