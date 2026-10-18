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
import java.util.Base64;
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
 * <p>Requests authenticate with the temporary basic-auth credentials of the test profile
 * (application-test.yml, TemporarySecurityConfiguration); Stage 5 replaces this mechanism with
 * the X-API-Key filter (D5).
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
	 * JSON floats as exact BigDecimal. Assertions compare digits, so the test-side mapper must
	 * not collapse them either.
	 */
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();

	private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
			.encodeToString("test-user:test-password".getBytes(StandardCharsets.UTF_8));

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

		// Posting 1 for the same eventId is DIFFERENT content: 409, not an idempotent 200.
		HttpResponse<String> oneInstead = post(eventJson(eventId, "user-precision", "payment.settled",
				"core-banking", "2026-10-18T12:00:00Z", "{\"amount\":1}"));
		assertThat(oneInstead.statusCode()).isEqualTo(409);
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
		// Jackson's default StreamReadConstraints limit is 1000 digits per number literal;
		// 1001 digits must surface as client error, never as a 500.
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
				.header("Authorization", BASIC_AUTH)
				.GET()
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode docs = JSON.readTree(response.body());

		JsonNode post = docs.get("paths").get(EVENT_PATH).get("post");
		assertThat(post).isNotNull();
		assertThat(post.get("summary").stringValue()).isEqualTo("Record an event");
		JsonNode responses = post.get("responses");
		assertThat(responses.has("200")).isTrue();
		assertThat(responses.has("201")).isTrue();
		assertThat(responses.has("400")).isTrue();
		assertThat(responses.has("409")).isTrue();
		assertThat(post.get("requestBody").get("content").get("application/json").get("schema")
				.get("$ref").stringValue()).endsWith("CreateEventRequest");

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
	// Helpers
	// ---------------------------------------------------------------------

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EVENT_PATH))
				.header("Content-Type", "application/json")
				.header("Authorization", BASIC_AUTH)
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
}
