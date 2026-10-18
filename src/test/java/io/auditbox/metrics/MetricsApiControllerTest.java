package io.auditbox.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 9.4: Micrometer counters in the write path and the API-key entry point, exposed
 * through the (key-protected) {@code /actuator/metrics} endpoint over real HTTP:
 *
 * <ul>
 *   <li>201 increments {@code auditbox.events.created}, 200 {@code auditbox.events.replayed},
 *       409 {@code auditbox.events.conflicts},</li>
 *   <li>a missing-key request increments {@code auditbox.auth.failures} with
 *       {@code reason=missing} (a wrong key with {@code reason=invalid}; the key itself is
 *       never a tag value),</li>
 *   <li>{@code /actuator/metrics/auditbox.events.created} answers 401 without a key and 200
 *       with it, carrying the metric name and a COUNT ≥ the increments the test made,</li>
 *   <li>a percent-encoded public path ({@code /actuator/%68ealth}) is served without a key
 *       and does not count as an auth failure (review F2).</li>
 * </ul>
 *
 * <p>Assertions are made against the counter values observed <em>before</em> each request
 * (deltas), so the test is deterministic even if other tests already incremented the same
 * shared counters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class MetricsApiControllerTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String API_KEY = "test-api-key";

	private static final String EVENT_PATH = "/api/v1/events";

	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	@Autowired
	private MeterRegistry meterRegistry;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void createReplayAndConflictIncrementTheirCounters() throws Exception {
		String eventId = uniqueEventId();
		double createdBefore = count(AuditMetrics.EVENTS_CREATED);
		double replayedBefore = count(AuditMetrics.EVENTS_REPLAYED);
		double conflictsBefore = count(AuditMetrics.EVENTS_CONFLICTS);

		String body = eventJson(eventId, 100);
		HttpResponse<String> created = post(body);
		HttpResponse<String> replay = post(body);
		HttpResponse<String> conflict = post(eventJson(eventId, 101));

		assertThat(created.statusCode()).isEqualTo(201);
		assertThat(replay.statusCode()).isEqualTo(200);
		assertThat(conflict.statusCode()).isEqualTo(409);

		assertThat(count(AuditMetrics.EVENTS_CREATED)).isEqualTo(createdBefore + 1);
		assertThat(count(AuditMetrics.EVENTS_REPLAYED)).isEqualTo(replayedBefore + 1);
		assertThat(count(AuditMetrics.EVENTS_CONFLICTS)).isEqualTo(conflictsBefore + 1);
	}

	@Test
	void missingKeyRequestIncrementsTheAuthFailureCounterAsMissing() throws Exception {
		double missingBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING);
		double invalidBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID);

		HttpResponse<String> response = getWithoutKey(EVENT_PATH);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING))
				.isEqualTo(missingBefore + 1);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID))
				.isEqualTo(invalidBefore);
	}

	@Test
	void wrongKeyRequestIncrementsTheAuthFailureCounterAsInvalidAndNeverTagsTheKey() throws Exception {
		double missingBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING);
		double invalidBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID);
		String wrongKey = "not-the-real-key-" + UUID.randomUUID();

		HttpResponse<String> response = getWithWrongKey(EVENT_PATH, wrongKey);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID))
				.isEqualTo(invalidBefore + 1);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING))
				.isEqualTo(missingBefore);
		// The key value must never appear anywhere in the metrics registry.
		meterRegistry.getMeters().stream()
				.map(meter -> meter.getId().getTags())
				.flatMap(tags -> tags.stream().map(tag -> tag.getValue()))
				.forEach(tagValue -> assertThat(tagValue).doesNotContain(wrongKey));
	}

	@Test
	void percentEncodedPublicPathIsServedAndNotCountedAsAuthFailure() throws Exception {
		double missingBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING);
		double invalidBefore = count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID);

		// Tomcat decodes the path for the security matcher, so the health endpoint answers
		// without a key; the counter must not see this as a failed key check (review F2).
		HttpResponse<String> response = getWithoutKey("/actuator/%68ealth");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_MISSING))
				.isEqualTo(missingBefore);
		assertThat(count(AuditMetrics.AUTH_FAILURES, AuditMetrics.AUTH_FAILURE_REASON_INVALID))
				.isEqualTo(invalidBefore);
	}

	@Test
	void metricsEndpointWithoutApiKeyIsRejectedWith401() throws Exception {
		HttpResponse<String> response = getWithoutKey("/actuator/metrics/" + AuditMetrics.EVENTS_CREATED);

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void metricsEndpointWithApiKeyReturnsTheCounterMeasurement() throws Exception {
		// Make sure the meter exists (the endpoint answers 404 for unknown metric names):
		// the run inside this scenario owns its counter increments.
		String eventId = uniqueEventId();
		double createdBefore = count(AuditMetrics.EVENTS_CREATED);
		assertThat(post(eventJson(eventId, 1)).statusCode()).isEqualTo(201);
		double afterCreate = count(AuditMetrics.EVENTS_CREATED);

		HttpResponse<String> response = getWithKey("/actuator/metrics/" + AuditMetrics.EVENTS_CREATED);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode metric = JSON.readTree(response.body());
		assertThat(metric.get("name").stringValue()).isEqualTo(AuditMetrics.EVENTS_CREATED);
		assertThat(metric.get("measurements").get(0).get("statistic").stringValue()).isEqualTo("COUNT");
		assertThat(metric.get("measurements").get(0).get("value").doubleValue())
				.isEqualTo(afterCreate)
				.isGreaterThanOrEqualTo(createdBefore + 1);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private double count(String name) {
		Counter counter = meterRegistry.find(name).counter();
		return counter == null ? 0.0 : counter.count();
	}

	private double count(String name, String reasonTagValue) {
		Counter counter = meterRegistry.find(name)
				.tag(AuditMetrics.AUTH_FAILURE_REASON_TAG, reasonTagValue)
				.counter();
		return counter == null ? 0.0 : counter.count();
	}

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EVENT_PATH))
				.header("Content-Type", "application/json")
				.header("X-API-Key", API_KEY)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String eventJson(String eventId, long amount) {
		return "{\"eventId\":\"" + eventId + "\",\"userId\":\"m9-metrics\",\"eventType\":\"payment.settled\","
				+ "\"source\":\"metrics-test\",\"occurredAt\":\"2026-10-19T12:00:00Z\","
				+ "\"metadata\":{\"amount\":" + amount + "}}";
	}

	private static String uniqueEventId() {
		return "m9-metrics-" + UUID.randomUUID();
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

	private HttpResponse<String> getWithWrongKey(String path, String wrongKey) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("X-API-Key", wrongKey)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
