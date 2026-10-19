package io.auditbox.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Stage 3 integration tests (plan tasks T3.1/T3.2) against a real PostgreSQL (Testcontainers,
 * same pinned image as docker-compose.yml) and real HTTP on a random port (required for the
 * concurrency scenarios — the unique-violation race must go through the full server stack).
 *
 * <p>Requests authenticate with the static X-API-Key of the test profile (application-test.yml,
 * {@code ApiKeySecurityConfiguration}); the key is the D5 authentication mechanism since Stage 5.
 *
 * <p>Every scenario uses a unique client-generated eventId so the tests are deterministic and
 * independent of execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EventApiControllerTest {

	/**
	 * Metadata is re-read in tests with the exact configuration production must use (D10):
	 * JSON floats as exact BigDecimal and the number-length constraint aligned with the
	 * validation bounds {@link JsonFormatMapperConfiguration}. Assertions compare digits, so
	 * the test-side mapper must not collapse them either.
	 */
	private static final JsonMapper JSON = jsonMapper();

	private static JsonMapper jsonMapper() {
		return JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
				.streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
						.maxNumberLength(JsonFormatMapperConfiguration.MAX_NUMBER_LENGTH)
						.build())
				.build())
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.build();
	}

	private static final String API_KEY = "test-api-key";

	private static final int CONCURRENT_REQUESTS = 20;

	private static final String EVENT_PATH = "/api/v1/events";

	/**
	 * Pinned PostgreSQL image; keep in sync with docker-compose.yml.
	 * Connection details are injected into Spring via {@code @ServiceConnection}.
	 */
	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private final HttpClient client = HttpClient.newHttpClient();

	// ---------------------------------------------------------------------
	// T3.2 acceptance: 201 / 200 / 409 semantics on real PostgreSQL
	// ---------------------------------------------------------------------

	@Test
	void newEventIsStoredAndReturnedWith201() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00.123456Z", "{\"amount\":125.5,\"currency\":\"EUR\",\"tags\":[\"card\"],\"note\":null}"));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

		JsonNode body = JSON.readTree(response.body());
		assertThat(body.get("id").longValue()).isPositive();
		assertThat(body.get("eventId").stringValue()).isEqualTo(eventId);
		assertThat(body.get("userId").stringValue()).isEqualTo("user-1");
		assertThat(body.get("eventType").stringValue()).isEqualTo("payment.settled");
		assertThat(body.get("source").stringValue()).isEqualTo("core-banking");
		// occurredAt round-trips at microsecond precision.
		assertThat(OffsetDateTime.parse(body.get("occurredAt").stringValue()).toInstant())
				.isEqualTo(Instant.parse("2026-10-18T12:00:00.123456Z"));
		assertThat(body.get("metadata").get("amount").asString()).isEqualTo("125.5");
		assertThat(body.get("metadata").get("currency").stringValue()).isEqualTo("EUR");
		assertThat(body.get("metadata").get("tags").get(0).stringValue()).isEqualTo("card");
		assertThat(body.get("metadata").get("note").isNull()).isTrue();
		// receivedAt is server-set (database clock at insert), not client-provided.
		OffsetDateTime receivedAt = OffsetDateTime.parse(body.get("receivedAt").stringValue());
		assertThat(receivedAt.toInstant()).isCloseTo(Instant.now(), within(60, ChronoUnit.SECONDS));
		// The internal content hash is never exposed.
		assertThat(response.body()).doesNotContain("content_hash").doesNotContain("contentHash");

		assertThat(eventRowCount(eventId)).isEqualTo(1);
		// The API-returned receivedAt is exactly the stored database value.
		OffsetDateTime dbReceivedAt = jdbcTemplate.queryForObject(
				"SELECT received_at FROM events WHERE event_id = ?", OffsetDateTime.class, eventId);
		assertThat(dbReceivedAt.toInstant()).isEqualTo(receivedAt.toInstant());
	}

	@Test
	void identicalRetryReturns200WithTheStoredRecordAndKeepsSingleRow() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-1", "payment.settled", "core-banking", "2026-10-18T12:00:00Z",
				"{\"amount\":100,\"currency\":\"EUR\"}");

		HttpResponse<String> first = post(body);
		HttpResponse<String> second = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(second.statusCode()).isEqualTo(200);
		// The replay returns the stored record: identical payload, same row id.
		assertThat(JSON.readTree(second.body())).isEqualTo(JSON.readTree(first.body()));
		assertThat(eventRowCount(eventId)).isEqualTo(1);

		// An equivalent reformulation hashes identically (D10): different offset spelling,
		// different key order, 100.00 instead of 100 -> still an idempotent 200, no 409.
		HttpResponse<String> equivalentForm = post(
				"{\"occurredAt\":\"2026-10-18T14:00:00+02:00\",\"metadata\":{\"currency\":\"EUR\",\"amount\":100.00},"
						+ "\"source\":\"core-banking\",\"eventType\":\"payment.settled\",\"userId\":\"user-1\",\"eventId\":\""
						+ eventId + "\"}");
		assertThat(equivalentForm.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		// The replay body is the stored record in both cases: equal to the original 201 body.
		assertThat(JSON.readTree(equivalentForm.body())).isEqualTo(JSON.readTree(first.body()));
	}

	@Test
	void firstSubmissionWithNonUtcOffsetReturnsTheUtcInstant() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-offset", "payment.settled", "core-banking",
				"2026-10-18T14:00:00+02:00", null));

		assertThat(response.statusCode()).isEqualTo(201);
		// The 201 response is rendered in UTC too, so it matches what a later re-read returns.
		String occurredAt = JSON.readTree(response.body()).get("occurredAt").stringValue();
		assertThat(OffsetDateTime.parse(occurredAt).toInstant()).isEqualTo(Instant.parse("2026-10-18T12:00:00Z"));
		assertThat(occurredAt).endsWith("Z");
	}

	@Test
	void absentAndExplicitNullMetadataAreTheSameContent() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> withoutMetadata = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", null));
		HttpResponse<String> withExplicitNull = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "null"));

		assertThat(withoutMetadata.statusCode()).isEqualTo(201);
		assertThat(JSON.readTree(withoutMetadata.body()).get("metadata").isNull()).isTrue();
		assertThat(withExplicitNull.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
	}

	static Stream<Arguments> differentContentVariants() {
		return Stream.of(
				Arguments.of("different userId", "user-2", "payment.settled", "core-banking", "2026-10-18T12:00:00Z",
						"{\"amount\":100}"),
				Arguments.of("different eventType", "user-1", "payment.refunded", "core-banking",
						"2026-10-18T12:00:00Z", "{\"amount\":100}"),
				Arguments.of("different source", "user-1", "payment.settled", "card-gateway", "2026-10-18T12:00:00Z",
						"{\"amount\":100}"),
				Arguments.of("different occurredAt", "user-1", "payment.settled", "core-banking",
						"2026-10-18T12:00:01Z", "{\"amount\":100}"),
				Arguments.of("different metadata value", "user-1", "payment.settled", "core-banking",
						"2026-10-18T12:00:00Z", "{\"amount\":101}"),
				Arguments.of("metadata absent instead of present", "user-1", "payment.settled", "core-banking",
						"2026-10-18T12:00:00Z", null));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("differentContentVariants")
	void sameEventIdWithDifferentContentReturns409WithExistingEvent(String label, String userId, String eventType,
			String source, String occurredAt, String metadata) throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> first = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":100}"));
		assertThat(first.statusCode()).isEqualTo(201);

		HttpResponse<String> second = post(eventJson(eventId, userId, eventType, source, occurredAt, metadata));

		assertThat(second.statusCode()).isEqualTo(409);
		assertThat(second.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");

		JsonNode problem = JSON.readTree(second.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Conflict");
		JsonNode existingEvent = problem.get("existingEvent");
		assertThat(existingEvent).isNotNull();
		// The stored record is returned in the same shape as a success response.
		assertThat(existingEvent).isEqualTo(JSON.readTree(first.body()));
		assertThat(existingEvent.get("eventId").stringValue()).isEqualTo(eventId);

		// Exactly one row, still the FIRST content: its stored content_hash must match the
		// hash of the first response's content fields.
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		JsonNode stored = JSON.readTree(first.body());
		String expectedHash = EventContentHasher.hash(stored.get("eventId").stringValue(),
				stored.get("userId").stringValue(), stored.get("eventType").stringValue(),
				stored.get("source").stringValue(), stored.get("occurredAt").stringValue(),
				stored.get("metadata"));
		String storedHash = jdbcTemplate.queryForObject(
				"SELECT content_hash FROM events WHERE event_id = ?", String.class, eventId);
		assertThat(storedHash).isEqualTo(expectedHash);
	}

	// ---------------------------------------------------------------------
	// T3.1 acceptance: validation -> 400 Problem Details with field errors
	// ---------------------------------------------------------------------

	static Stream<Arguments> invalidBodies() {
		String base = "{\"eventId\":\"%s\",\"userId\":\"user-1\",\"eventType\":\"payment.settled\","
				+ "\"source\":\"core-banking\",\"occurredAt\":\"%s\"}";
		return Stream.of(
				Arguments.of("blank eventId",
						"{\"eventId\":\" \",\"userId\":\"user-1\",\"eventType\":\"payment.settled\","
								+ "\"source\":\"core-banking\",\"occurredAt\":\"2026-10-18T12:00:00Z\"}",
						"eventId"),
				Arguments.of("missing userId",
						"{\"eventId\":\"validation-missing-user\",\"eventType\":\"payment.settled\","
								+ "\"source\":\"core-banking\",\"occurredAt\":\"2026-10-18T12:00:00Z\"}",
						"userId"),
				Arguments.of("blank eventType",
						"{\"eventId\":\"validation-blank-type\",\"userId\":\"user-1\",\"eventType\":\" \","
								+ "\"source\":\"core-banking\",\"occurredAt\":\"2026-10-18T12:00:00Z\"}",
						"eventType"),
				Arguments.of("oversized source (129 characters)",
						"{\"eventId\":\"validation-long-source\",\"userId\":\"user-1\","
								+ "\"eventType\":\"payment.settled\",\"source\":\"" + "s".repeat(129) + "\","
								+ "\"occurredAt\":\"2026-10-18T12:00:00Z\"}",
						"source"),
				Arguments.of("occurredAt without offset",
						base.formatted("validation-no-offset", "2026-10-18T12:00:00"), "occurredAt"),
				Arguments.of("occurredAt not ISO-8601",
						base.formatted("validation-not-iso", "18-10-2026"), "occurredAt"),
				Arguments.of("occurredAt above the supported range",
						base.formatted("validation-above-range", "+10000-01-01T00:00:00Z"), "occurredAt"),
				Arguments.of("occurredAt below the supported range",
						base.formatted("validation-below-range", "0000-12-31T23:59:59Z"), "occurredAt"),
				Arguments.of("occurredAt missing entirely",
						"{\"eventId\":\"validation-no-time\",\"userId\":\"user-1\",\"eventType\":\"payment.settled\","
								+ "\"source\":\"core-banking\"}",
						"occurredAt"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidBodies")
	void invalidRequestsReturn400ProblemDetailsWithFieldErrors(String label, String body, String expectedField)
			throws Exception {
		HttpResponse<String> response = post(body);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");

		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("status").intValue()).isEqualTo(400);
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(problem.get("errors")).isNotNull();
		List<String> fields = new ArrayList<>();
		problem.get("errors").forEach(error -> {
			fields.add(error.get("field").stringValue());
			assertThat(error.get("message").stringValue()).isNotBlank();
		});
		assertThat(fields).contains(expectedField);
	}

	@Test
	void occurredAtAtTheSupportedRangeBoundsIsAcceptedAndReplays() throws Exception {
		// Review round 3b, F-1: the write path enforces the same range the cursor decode
		// accepts, so even boundary instants are storable and their cursors replay.
		for (String occurredAt : new String[] { "0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z" }) {
			String eventId = uniqueEventId();
			String body = eventJson(eventId, "user-range", "payment.settled", "core-banking", occurredAt, null);

			HttpResponse<String> first = post(body);
			HttpResponse<String> retry = post(body);

			assertThat(first.statusCode()).isEqualTo(201);
			assertThat(retry.statusCode()).isEqualTo(200);
			assertThat(eventRowCount(eventId)).isEqualTo(1);
		}
	}

	@Test
	void malformedJsonReturns400ProblemDetailsWithAGenericDetail() throws Exception {
		HttpResponse<String> response = post("{not json");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(problem.get("detail").stringValue()).isEqualTo("Malformed or unreadable request body");
		// The errors extension is reserved for Bean Validation; a malformed body must not
		// leak parser internals (review round 6, F2).
		assertThat(problem.has("errors")).isFalse();
		assertThat(response.body()).doesNotContain("org.", "Exception");
	}

	// ---------------------------------------------------------------------
	// T3.2 acceptance: concurrency on real HTTP
	// ---------------------------------------------------------------------

	@Test
	void concurrentIdenticalRequestsProduceExactlyOne201AndExactlyOneRow() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-1", "payment.settled", "core-banking", "2026-10-18T12:00:00Z",
				"{\"amount\":42}");

		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
		CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			tasks.add(() -> {
				// Maximize the chance of a genuine insert race: all 20 requests fire together.
				startBarrier.await(10, TimeUnit.SECONDS);
				return post(body).statusCode();
			});
		}

		List<Integer> statuses = new ArrayList<>();
		try {
			for (Future<Integer> future : executor.invokeAll(tasks)) {
				// Any request that failed on the client side fails the test right here.
				statuses.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).hasSize(CONCURRENT_REQUESTS);
		assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
		assertThat(statuses).filteredOn(status -> status == 200).hasSize(CONCURRENT_REQUESTS - 1);
		assertThat(statuses).noneMatch(status -> status >= 500);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
	}

	@Test
	void moreConcurrentDistinctWritesThanThePoolSizeAllSucceed() throws Exception {
		// M11 review F1 regression: the write path needs one pool connection per request
		// (previously two), so 40 concurrent distinct writes against the shipped pool of
		// 32 must all succeed — before the fix they all failed with 5xx/Hikari timeouts.
		int concurrentWrites = 40;
		List<String> eventIds = new ArrayList<>();
		for (int i = 0; i < concurrentWrites; i++) {
			eventIds.add(uniqueEventId());
		}

		ExecutorService executor = Executors.newFixedThreadPool(concurrentWrites);
		CyclicBarrier startBarrier = new CyclicBarrier(concurrentWrites);
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (String eventId : eventIds) {
			String body = eventJson(eventId, "user-f1", "payment.settled", "core-banking",
					"2026-10-18T12:00:00Z", null);
			tasks.add(() -> {
				startBarrier.await(10, TimeUnit.SECONDS);
				return post(body).statusCode();
			});
		}

		List<Integer> statuses = new ArrayList<>();
		try {
			for (Future<Integer> future : executor.invokeAll(tasks)) {
				statuses.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).hasSize(concurrentWrites);
		assertThat(statuses).allMatch(status -> status == 201);
		for (String eventId : eventIds) {
			assertThat(eventRowCount(eventId)).isEqualTo(1);
		}
	}

	@Test
	void concurrentDifferentContentRequestsProduceOne201AndOne409AndOneRow() throws Exception {
		String eventId = uniqueEventId();
		String first = eventJson(eventId, "user-1", "payment.settled", "core-banking", "2026-10-18T12:00:00Z",
				"{\"amount\":100}");
		String second = eventJson(eventId, "user-1", "payment.settled", "core-banking", "2026-10-18T12:00:00Z",
				"{\"amount\":101}");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier startBarrier = new CyclicBarrier(2);
		List<Callable<Integer>> tasks = List.of(
				() -> {
					startBarrier.await(10, TimeUnit.SECONDS);
					return post(first).statusCode();
				},
				() -> {
					startBarrier.await(10, TimeUnit.SECONDS);
					return post(second).statusCode();
				});

		List<Integer> statuses = new ArrayList<>();
		try {
			for (Future<Integer> future : executor.invokeAll(tasks)) {
				statuses.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------
	// D10 acceptance: metadata precision and real jsonb storage
	// ---------------------------------------------------------------------

	@Test
	void highPrecisionMetadataSurvivesEndToEndAsExactJsonb() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-precision", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":1.00000000000000000001}"));

		assertThat(response.statusCode()).isEqualTo(201);
		// The response carries the exact digits (read back from the database through
		// Hibernate with the BigDecimal format mapper).
		assertThat(JSON.readTree(response.body()).get("metadata").get("amount").asString())
				.isEqualTo("1.00000000000000000001");

		// The column is real jsonb holding the exact numeric, not a doubly-encoded string.
		assertThat(jsonbType(eventId, "metadata")).isEqualTo("object");
		assertThat(jsonbText(eventId, "metadata->>'amount'")).isEqualTo("1.00000000000000000001");

		// A retry reads the jsonb column back through Hibernate end to end: the 200 body
		// must carry the exact digits too (review round 2, F5).
		HttpResponse<String> retry = post(eventJson(eventId, "user-precision", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1.00000000000000000001}"));
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(retry.body()).get("metadata").get("amount").asString())
				.isEqualTo("1.00000000000000000001");

		// Posting 1 for the same eventId is DIFFERENT content: 409, and the stored record in
		// the existingEvent extension (read FROM the database right here) carries the exact
		// digits as well, not just the 201 in-memory response (review round 2, F5).
		HttpResponse<String> oneInstead = post(eventJson(eventId, "user-precision", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1}"));
		assertThat(oneInstead.statusCode()).isEqualTo(409);
		JsonNode existingEvent = JSON.readTree(oneInstead.body()).get("existingEvent");
		assertThat(existingEvent.get("metadata").get("amount").asString())
				.isEqualTo("1.00000000000000000001");
		assertThat(eventRowCount(eventId)).isEqualTo(1);
	}

	@Test
	void nestedMetadataRoundTripsAndIsStoredAsRealJsonb() throws Exception {
		String eventId = uniqueEventId();
		String metadata = "{\"order\":{\"lines\":[{\"sku\":\"A-1\",\"qty\":2},{\"sku\":\"B-2\",\"qty\":1}],"
				+ "\"total\":3},\"flag\":true,\"note\":null,\"label\":\"caf\\u00e9\"}";

		HttpResponse<String> response = post(eventJson(eventId, "user-nested", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", metadata));

		assertThat(response.statusCode()).isEqualTo(201);
		// The full nested structure round-trips through storage and the API.
		assertThat(JSON.readTree(response.body()).get("metadata")).isEqualTo(JSON.readTree(metadata));

		// Real jsonb at every level: jsonb_typeof, ->> and nested path expressions all work.
		assertThat(jsonbType(eventId, "metadata")).isEqualTo("object");
		assertThat(jsonbType(eventId, "metadata->'order'")).isEqualTo("object");
		assertThat(jsonbType(eventId, "metadata->'order'->'lines'")).isEqualTo("array");
		assertThat(jsonbText(eventId, "metadata->>'label'")).isEqualTo("café");
		assertThat(jsonbText(eventId, "metadata->'order'->>'total'")).isEqualTo("3");
		assertThat(jsonbText(eventId, "metadata#>>'{order,lines,1,sku}'")).isEqualTo("B-2");
	}

	@Test
	void oversizedNumberInMetadataReturns400Not500() throws Exception {
		String eventId = uniqueEventId();
		// A number literal one digit beyond the supported bound must surface as a client
		// error with a precise metadata field error, never as a 500 (review round 2, F1/F2;
		// the reject itself is bounded by ValidMetadataValidator).
		String oversized = "{\"eventId\":\"%s\",\"userId\":\"user-1\",\"eventType\":\"payment.settled\","
				+ "\"source\":\"core-banking\",\"occurredAt\":\"2026-10-18T12:00:00Z\","
				+ "\"metadata\":{\"amount\":%s}}".formatted(eventId, "9".repeat(1001));

		HttpResponse<String> response = post(oversized);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	// ---------------------------------------------------------------------
	// Review round 2, F1/F2: metadata number bounds at validation
	// ---------------------------------------------------------------------

	/**
	 * 1e999 canonicalizes to exactly 1000 integer digits: inside the bound (F1), stored and
	 * idempotently replayed through the jsonb read path.
	 */
	@Test
	void metadataNumberAtTheIntegerDigitBoundIsAcceptedAndReplays() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-number-bound", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":1e999}");

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		// Exactly the 1000 canonical integer digits, rendered as a plain decimal in both
		// bodies (F4): the 201 in-memory form and the 200 jsonb-read form agree.
		String expectedDigits = "\"amount\":1" + "0".repeat(999);
		assertThat(first.body()).contains(expectedDigits).doesNotContain("E+");
		assertThat(retry.body()).isEqualTo(first.body());
	}

	/**
	 * 1e1000 canonicalizes to 1001 integer digits: rejected with a metadata field error
	 * (today it would be accepted with 201 and then fail unreadable on the read path), no
	 * row stored.
	 */
	@Test
	void metadataNumberBeyondTheIntegerDigitBoundReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-number-bound", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1e1000}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(fieldNames(problem)).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * A 12-byte literal with a ten-digit exponent is rejected in constant time, before any
	 * large allocation could happen (F1). The timing bound is deliberately loose (it bounds
	 * the absence of OOM/expansion, not network latency).
	 */
	@Test
	void metadataNumberWithHugeExponentReturns400Quickly() throws Exception {
		String eventId = uniqueEventId();

		long started = System.nanoTime();
		HttpResponse<String> response = post(eventJson(eventId, "user-number-huge", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1e2000000000}"));
		long durationMs = (System.nanoTime() - started) / 1_000_000;

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(fieldNames(JSON.readTree(response.body()))).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
		assertThat(durationMs).isLessThan(2000);
	}

	/**
	 * 1e-1000 canonicalizes to exactly 1000 fractional digits: inside the bound (F1);
	 * the plain-decimal jsonb form (0.000...1, 1001 digits) read-back works end to end.
	 */
	@Test
	void metadataNumberAtTheFractionDigitBoundIsAcceptedAndReplays() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-fraction-bound", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"rate\":1e-1000}");
		String expectedPlainForm = "\"rate\":0." + "0".repeat(999) + "1";

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(first.body()).contains(expectedPlainForm).doesNotContain("E-");
		assertThat(retry.body()).isEqualTo(first.body());
	}

	/**
	 * 1e-1001 canonicalizes to 1001 fractional digits: beyond the bound, 400 with a
	 * metadata field error, no row stored.
	 */
	@Test
	void metadataNumberBeyondTheFractionDigitBoundReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-fraction-bound", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"rate\":1e-1001}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(fieldNames(JSON.readTree(response.body()))).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * The widest accepted shape — 1000 integer and 1000 fractional digits at once — renders
	 * to a 2001-digit plain decimal both on the wire (echo-back retry) and in the jsonb
	 * read-back; this is exactly what the number-length read constraints are raised above
	 * the default 1000 digits for (F1/F2) — an accepted value must never become an
	 * unreadable 500 on replay.
	 */
	@Test
	void metadataNumberUsingBothDigitBoundsEchoesBackIdempotently() throws Exception {
		String eventId = uniqueEventId();
		String number = digits(1000) + "." + digits(1000);
		String body = eventJson(eventId, "user-wide-bound", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":" + number + "}");
		String expectedPlainForm = "\"amount\":" + number;

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(first.body()).contains(expectedPlainForm).doesNotContain("E+");
		assertThat(JSON.readTree(retry.body())).isEqualTo(JSON.readTree(first.body()));
		assertThat(jsonbText(eventId, "metadata->>'amount'")).isEqualTo(number);
	}

	// ---------------------------------------------------------------------
	// Review round 2 re-review blockers B1/B2: the stored (unstripped) form
	// is bounded too, and exponent parsing cannot overflow digit arithmetic
	// ---------------------------------------------------------------------

	/**
	 * B1 boundary. For the literal {@code "1." + "0".repeat(n) + "e-999"} the parsed
	 * instance has precision {@code n + 1} and scale {@code n + 999}, so its stored plain
	 * form has {@code max(precision - scale, 0) + max(scale, 0) = n + 999} digits while the
	 * canonical form stays 1e-999 (999 fractional digits). {@code n = 3001} puts exactly
	 * 4000 stored digits at the bound: accepted, stored, and idempotently replayed with the
	 * literal's scale intact (jsonb numeric keeps all 4000 fractional digits).
	 */
	@Test
	void metadataNumberAtTheStoredDigitBoundIsAcceptedAndReplays() throws Exception {
		String eventId = uniqueEventId();
		// The stored plain form of the parsed instance: "0." + 998 zeros + 1 + 3001 zeros
		// (4000 fractional digits — the literal's scale, kept by the parser and by jsonb).
		String storedForm = "0." + "0".repeat(998) + "1" + "0".repeat(3001);
		String body = eventJson(eventId, "user-stored-bound", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":1." + "0".repeat(3001) + "e-999}");
		String expectedPlainForm = "\"amount\":" + storedForm;

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(first.body()).contains(expectedPlainForm).doesNotContain("E-");
		// The jsonb read-back preserves the stored scale (4000 fractional digits), so the
		// replayed body renders identically and the echo parses under the 4096 limit.
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(jsonbText(eventId, "metadata->>'amount'")).isEqualTo(storedForm);
	}

	/**
	 * B1: the literal from the re-review report — one zero more than the accepted boundary
	 * above ({@code n = 3098}, 4097 stored digits, a plain form above the 4096-digit read
	 * limit) passed the canonical bounds (999 fractional digits) and failed the jsonb insert
	 * itself with a 500. The stored-form bound rejects it at validation with a metadata field
	 * error, no row stored.
	 */
	@Test
	void metadataNumberBeyondTheStoredDigitBoundReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-stored-bound", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1." + "0".repeat(3098) + "e-999}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(fieldNames(JSON.readTree(response.body()))).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * B2: the exponent 2147483647 overflows 32-bit digit arithmetic (precision 1, scale
	 * -2147483647; in int arithmetic precision - scale wraps negative and passed every
	 * bound, and the hash then tried to materialize 2147483648 digits in toPlainString()).
	 * Long arithmetic rejects at validation: 400 with a metadata field error, no row, no
	 * 500, in constant time (the timing bound is deliberately loose, as above).
	 */
	@Test
	void metadataNumberWithIntOverflowingExponentReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		long started = System.nanoTime();
		HttpResponse<String> response = post(eventJson(eventId, "user-exponent-overflow", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1e2147483647}"));
		long durationMs = (System.nanoTime() - started) / 1_000_000;

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(fieldNames(JSON.readTree(response.body()))).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
		assertThat(durationMs).isLessThan(2000);
	}

	/**
	 * B2 companion: an exponent one beyond Integer.MAX_VALUE cannot even parse (the parser
	 * rejects the literal, D12) — the same 400 contract, without a metadata field error
	 * because the body never reaches Bean Validation, and no 500.
	 */
	@Test
	void metadataNumberWithUnparsableExponentReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-exponent-parse", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1e2147483648}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * Re-review 2c F1: 100e2147483647 carries trailing zeros plus a scale near
	 * Integer.MIN_VALUE. stripTrailingZeros() used to overflow inside the validator,
	 * surfacing as a 500; the unstripped integer-digit check now rejects it with a metadata
	 * field error and no row.
	 */
	@Test
	void metadataNumberWithTrailingZeroExponentReturns400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-strip-overflow", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":100e2147483647}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(fieldNames(JSON.readTree(response.body()))).contains("metadata");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * Re-review 2c F5: the negative twin of the stored-digit boundary is the widest accepted
	 * rendering (4003 characters); it must store and replay like the positive one.
	 */
	@Test
	void negativeMetadataNumberAtTheStoredDigitBoundIsAcceptedAndReplays() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-negative-bound", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":-1." + "0".repeat(3001) + "e-999}");

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(first.body()).contains("\"amount\":-0.").doesNotContain("E-");
	}

	private static String digits(int count) {
		return "9".repeat(count);
	}

	// ---------------------------------------------------------------------
	// Review round 2, F4: stable plain-decimal rendering in responses
	// ---------------------------------------------------------------------

	/**
	 * The 201 in-memory body and the 200/409 jsonb-read form must render BigDecimal values
	 * identically: plain decimals (1e2 is 100, 1e-7 is 0.0000001), never scientific
	 * notation. The retry echoes the API's own body verbatim and stays idempotent.
	 */
	@Test
	void decimalMetadataNumbersRenderAsPlainDecimalsInEveryResponse() throws Exception {
		String eventId = uniqueEventId();
		String body = eventJson(eventId, "user-plain", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":1e2,\"rate\":1e-7}");

		HttpResponse<String> first = post(body);
		HttpResponse<String> retry = post(body);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(first.body()).contains("\"amount\":100", "\"rate\":0.0000001")
				.doesNotContain("E+").doesNotContain("E-");
		// The jsonb-read form renders identically (plain decimals; jsonb only normalizes
		// object key order), so echoing the response back must be an idempotent retry, not
		// a parse failure or a false conflict.
		assertThat(retry.body()).contains("\"amount\":100", "\"rate\":0.0000001")
				.doesNotContain("E+").doesNotContain("E-");
		assertThat(JSON.readTree(retry.body())).isEqualTo(JSON.readTree(first.body()));
	}

	// ---------------------------------------------------------------------
	// Review round 2, F3: client-caused SQL data errors are 400, not 500
	// ---------------------------------------------------------------------

	/**
	 * PostgreSQL rejects a NUL byte in a text column at insert time (SQLState 22021, "data
	 * exception"): the request is at fault, so the response is 400, not 500, and no row is
	 * stored.
	 */
	@Test
	void nullCharacterInUserIdSurfacesAs400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post("{\"eventId\":\"%s\",\"userId\":\"user\\u0000-1\","
				+ "\"eventType\":\"payment.settled\",\"source\":\"core-banking\","
				+ "\"occurredAt\":\"2026-10-18T12:00:00Z\"}".formatted(eventId));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	/**
	 * PostgreSQL jsonb cannot store a NUL inside a string (SQLState 22P05): same client-error
	 * treatment as the text-column case (F3).
	 */
	@Test
	void nullCharacterInsideMetadataStringSurfacesAs400AndStoresNoRow() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> response = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"note\":\"before\\u0000after\"}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(eventRowCount(eventId)).isEqualTo(0);
	}

	// ---------------------------------------------------------------------
	// D10 acceptance: microsecond precision makes an echoed occurredAt safe
	// ---------------------------------------------------------------------

	@Test
	void retryEchoingTheReturnedOccurredAtDoesNotFalseConflict() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> first = post(eventJson(eventId, "user-echo", "payment.settled", "core-banking",
				"2026-10-18T12:00:00.123456789Z", "{\"amount\":1}"));
		assertThat(first.statusCode()).isEqualTo(201);

		// A retry that echoes the occurredAt the API itself returned (microsecond precision)
		// must be recognized as identical content (D10), not produce a false 409.
		String echoedOccurredAt = JSON.readTree(first.body()).get("occurredAt").stringValue();
		HttpResponse<String> retry = post(eventJson(eventId, "user-echo", "payment.settled", "core-banking",
				echoedOccurredAt, "{\"amount\":1}"));

		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		// The stored instant is truncated to microseconds, matching the hash normalization.
		assertThat(OffsetDateTime.parse(echoedOccurredAt).toInstant())
				.isEqualTo(Instant.parse("2026-10-18T12:00:00.123456Z"));
	}

	// ---------------------------------------------------------------------
	// T3.1 acceptance: OpenAPI documentation
	// ---------------------------------------------------------------------

	@Test
	void openApiDocsDescribeTheEventsPath() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
				.header("X-API-Key", API_KEY)
				.GET()
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode docs = JSON.readTree(response.body());

		JsonNode eventPath = docs.get("paths").get(EVENT_PATH);
		JsonNode post = eventPath.get("post");
		assertThat(post).isNotNull();
		assertThat(post.get("summary").stringValue()).isEqualTo("Record an event");
		JsonNode responses = post.get("responses");
		assertThat(responses.has("200")).isTrue();
		assertThat(responses.has("201")).isTrue();
		assertThat(responses.has("400")).isTrue();
		assertThat(responses.has("409")).isTrue();
		assertThat(post.get("requestBody").get("content").get("application/json").get("schema")
				.get("$ref").stringValue()).endsWith("CreateEventRequest");

		// Milestone 9.3: the single-event fetch is documented too (its own path), with its 200 and 404.
		JsonNode getById = docs.get("paths").get(EVENT_PATH + "/{eventId}").get("get");
		assertThat(getById).isNotNull();
		assertThat(getById.get("summary").stringValue()).isEqualTo("Fetch a single event");
		JsonNode getByIdResponses = getById.get("responses");
		assertThat(getByIdResponses.has("200")).isTrue();
		assertThat(getByIdResponses.has("404")).isTrue();

		JsonNode createSchema = docs.get("components").get("schemas").get("CreateEventRequest");
		assertThat(createSchema).isNotNull();
		assertThat(createSchema.get("required").toString())
				.contains("eventId").contains("userId").contains("eventType").contains("source")
				.contains("occurredAt");
		JsonNode eventIdProperty = createSchema.get("properties").get("eventId");
		assertThat(eventIdProperty.get("maxLength").intValue()).isEqualTo(128);
		JsonNode occurredAtProperty = createSchema.get("properties").get("occurredAt");
		assertThat(occurredAtProperty.get("format").stringValue()).isEqualTo("date-time");
	}

	// ---------------------------------------------------------------------
	// Milestone 9.3: GET /api/v1/events/{eventId}
	// ---------------------------------------------------------------------

	/**
	 * The single-event fetch returns exactly the body the original 201 returned (same
	 * shape, same stored record — the jsonb read path renders identically).
	 */
	@Test
	void getReturnsTheStoredEventByEventId() throws Exception {
		String eventId = uniqueEventId();

		HttpResponse<String> created = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00.123456Z", "{\"amount\":7,\"currency\":\"EUR\"}"));
		assertThat(created.statusCode()).isEqualTo(201);

		HttpResponse<String> response = getWithKey(EVENT_PATH + "/" + eventId);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(created.body()));
		assertThat(JSON.readTree(response.body()).get("eventId").stringValue()).isEqualTo(eventId);
	}

	@Test
	void getUnknownEventIdReturns404ProblemDetailsWithGenericDetail() throws Exception {
		HttpResponse<String> response = getWithKey(EVENT_PATH + "/no-event-" + UUID.randomUUID());

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("status").intValue()).isEqualTo(404);
		assertThat(problem.get("title").stringValue()).isEqualTo("Not Found");
		// Generic detail: no internals, no leak of storage errors.
		assertThat(problem.get("detail").stringValue()).isEqualTo("Event not found");
		assertThat(response.body()).doesNotContain("org.", "Exception");
	}

	@Test
	void getWithoutApiKeyIsRejectedWith401() throws Exception {
		HttpResponse<String> response = getWithoutKey(EVENT_PATH + "/" + uniqueEventId());

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).get("title").stringValue()).isEqualTo("Unauthorized");
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EVENT_PATH))
				.header("Content-Type", "application/json")
				.header("X-API-Key", API_KEY)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Builds a request body; {@code metadataJson} may be null (field omitted) or any JSON value. */
	private static String eventJson(String eventId, String userId, String eventType, String source,
			String occurredAt, String metadataJson) {
		StringBuilder json = new StringBuilder()
				.append("{\"eventId\":\"").append(eventId).append('"')
				.append(",\"userId\":\"").append(userId).append('"')
				.append(",\"eventType\":\"").append(eventType).append('"')
				.append(",\"source\":\"").append(source).append('"')
				.append(",\"occurredAt\":\"").append(occurredAt).append('"');
		if (metadataJson != null) {
			json.append(",\"metadata\":").append(metadataJson);
		}
		return json.append('}').toString();
	}

	private static String uniqueEventId() {
		return "t3-" + UUID.randomUUID();
	}

	private HttpResponse<String> getWithoutKey(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getWithKey(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("X-API-Key", API_KEY)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private int eventRowCount(String eventId) {
		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM events WHERE event_id = ?",
				Integer.class, eventId);
		return count == null ? 0 : count;
	}

	private String jsonbText(String eventId, String jsonExpression) {
		return jdbcTemplate.queryForObject("SELECT (" + jsonExpression + ")::text FROM events WHERE event_id = ?",
				String.class, eventId);
	}

	private String jsonbType(String eventId, String jsonExpression) {
		return jdbcTemplate.queryForObject("SELECT jsonb_typeof(" + jsonExpression + ") FROM events WHERE event_id = ?",
				String.class, eventId);
	}

	/** The violated fields of a 400 Problem Details body (the errors extension of T3.1). */
	private static List<String> fieldNames(JsonNode problem) {
		List<String> fields = new ArrayList<>();
		problem.get("errors").forEach(error -> fields.add(error.get("field").stringValue()));
		return List.copyOf(fields);
	}
}
