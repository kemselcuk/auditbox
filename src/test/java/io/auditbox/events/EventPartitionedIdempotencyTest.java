package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M11.1 integration tests (decision D21) against the real partitioned stack: rows must
 * route by occurred_at — managed months into their events_YYYY_MM partition, anything
 * else in the documented API range into the DEFAULT partition — while the single-logical-
 * table API (GET by eventId, GET list) stays unaware of which child partition holds a
 * row. The D4 idempotency guarantee must also survive the loss of the V1 global unique
 * constraint on (event_id): the same eventId at a DIFFERENT occurredAt is different
 * content AND different partition content, which only the advisory lock can catch —
 * proven both sequentially and concurrently here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EventPartitionedIdempotencyTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String API_KEY = "test-api-key";

	private static final String EVENT_PATH = "/api/v1/events";

	/**
	 * Pinned PostgreSQL image; keep in sync with docker-compose.yml. Connection details are
	 * injected into Spring via {@code @ServiceConnection}.
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
	// Partition routing through the write path
	// ---------------------------------------------------------------------

	@Test
	void eventsRouteIntoMonthlyAndDefaultPartitionsAndReadsUnifyThem() throws Exception {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		// 6 months back is inside the V2 window (current - 25 .. + 3).
		YearMonth managedOld = current.minusMonths(6);
		String currentMonthEvent = uniqueEventId();
		String managedOldEvent = uniqueEventId();
		String outOfWindowPastEvent = uniqueEventId();
		String outOfWindowFutureEvent = uniqueEventId();

		HttpResponse<String> createdCurrent = post(eventJson(currentMonthEvent, "user-routing",
				"payment.settled", "core-banking", isoUtc(current.getYear(), current.getMonthValue(), 15), null));
		assertThat(createdCurrent.statusCode()).isEqualTo(201);

		HttpResponse<String> createdOld = post(eventJson(managedOldEvent, "user-routing", "payment.settled",
				"core-banking", isoUtc(managedOld.getYear(), managedOld.getMonthValue(), 15), null));
		assertThat(createdOld.statusCode()).isEqualTo(201);

		// Years outside the managed window but inside the documented API range (D13):
		// both must land in the DEFAULT partition (D21).
		HttpResponse<String> createdPast = post(eventJson(outOfWindowPastEvent, "user-routing",
				"payment.settled", "core-banking", "2020-01-15T12:00:00Z", null));
		assertThat(createdPast.statusCode()).isEqualTo(201);
		HttpResponse<String> createdFuture = post(eventJson(outOfWindowFutureEvent, "user-routing",
				"payment.settled", "core-banking", "9999-12-31T23:59:59Z", null));
		assertThat(createdFuture.statusCode()).isEqualTo(201);

		// Routing: managed months in their own partition (pg_class view over tableoid),
		// everything else in the default partition.
		assertThat(partitionOf(currentMonthEvent)).isEqualTo(partitionName(current));
		assertThat(partitionOf(managedOldEvent)).isEqualTo(partitionName(managedOld));
		assertThat(partitionOf(outOfWindowPastEvent)).isEqualTo("events_default");
		assertThat(partitionOf(outOfWindowFutureEvent)).isEqualTo("events_default");

		// GET by eventId returns the row regardless of which partition holds it (D21:
		// the lookup scans every partition's index through the partitioned parent).
		for (String eventId : List.of(currentMonthEvent, managedOldEvent, outOfWindowPastEvent,
				outOfWindowFutureEvent)) {
			HttpResponse<String> response = getWithKey(EVENT_PATH + "/" + eventId);
			assertThat(response.statusCode()).as(eventId).isEqualTo(200);
			assertThat(JSON.readTree(response.body()).get("eventId").stringValue()).isEqualTo(eventId);
		}

		// The list endpoint unifies the partitions: a walk by userId sees all four rows,
		// newest first, even though three different partitions (weekly... monthly + default)
		// supply them.
		HttpResponse<String> page = getWithKey(EVENT_PATH + "?userId=user-routing&size=50");
		assertThat(page.statusCode()).isEqualTo(200);
		JsonNode body = JSON.readTree(page.body());
		List<String> listed = new ArrayList<>();
		body.get("items").forEach(item -> listed.add(item.get("eventId").stringValue()));
		assertThat(listed).containsExactlyInAnyOrder(currentMonthEvent, managedOldEvent, outOfWindowPastEvent,
				outOfWindowFutureEvent);
		// Keyset order (D3) still holds across partitions: 2020 row is OLDEST, 9999 row NEWEST.
		assertThat(listed.getFirst()).isEqualTo(outOfWindowFutureEvent);
		assertThat(listed.getLast()).isEqualTo(outOfWindowPastEvent);
	}

	// ---------------------------------------------------------------------
	// D4 idempotency across partitions (only the advisory lock can give this)
	// ---------------------------------------------------------------------

	@Test
	void sameEventIdWithDifferentOccurredAtIsCaughtSequentially() throws Exception {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		YearMonth next = current.plusMonths(1);
		String currentMonth = isoUtc(current.getYear(), current.getMonthValue(), 18);
		String nextMonth = isoUtc(next.getYear(), next.getMonthValue(), 18);
		String expectedStoredOccurredAt = "%04d-%02d-18T12:00:00Z".formatted(current.getYear(), current.getMonthValue());
		String expectedStoredPartition = partitionName(current);
		String eventId = uniqueEventId();

		HttpResponse<String> first = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				currentMonth, "{\"amount\":100}"));
		assertThat(first.statusCode()).isEqualTo(201);

		// Same eventId, different occurredAt (another partition): different content hash →
		// 409 conflict, existingEvent extension carries the FIRST stored record.
		HttpResponse<String> conflict = post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				nextMonth, "{\"amount\":100}"));
		assertThat(conflict.statusCode()).isEqualTo(409);
		assertThat(conflict.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(conflict.body());
		assertThat(problem.get("status").intValue()).isEqualTo(409);

		// Exactly one row stored: the first one, in its original partition, and a GET
		// returns the stored (first) occurredAt.
		assertThat(eventRowCount(eventId)).isEqualTo(1);
		assertThat(partitionOf(eventId)).isEqualTo(expectedStoredPartition);
		HttpResponse<String> stored = getWithKey(EVENT_PATH + "/" + eventId);
		assertThat(JSON.readTree(stored.body()).get("occurredAt").stringValue())
				.isEqualTo(expectedStoredOccurredAt);

		// The identical retry (same occurredAt as the stored row) still replays as 200.
		assertThat(post(eventJson(eventId, "user-1", "payment.settled", "core-banking",
				currentMonth, "{\"amount\":100}")).statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(conflict.body()).get("existingEvent").get("eventId").stringValue())
				.isEqualTo(eventId);
	}

	@Test
	void concurrentSameEventIdWithDifferentOccurredAtProducesOne201One409AndOneRow() throws Exception {
		String eventId = uniqueEventId();
		String first = eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-10-18T12:00:00Z", "{\"amount\":100}");
		String second = eventJson(eventId, "user-1", "payment.settled", "core-banking",
				"2026-11-18T12:00:00Z", "{\"amount\":100}");

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

		// The advisory lock serializes both writes: the winner stores, the loser pre-checks
		// under the lock and loses on the content hash — exactly one row, no 23505 leak (D21).
		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(statuses).noneMatch(status -> status >= 500);
		assertThat(eventRowCount(eventId)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static String isoUtc(int year, int month, int day) {
		return "%04d-%02d-%02dT12:00:00Z".formatted(year, month, day);
	}

	private static String partitionName(YearMonth month) {
		return "events_" + "%04d_%02d".formatted(month.getYear(), month.getMonthValue());
	}

	private String partitionOf(String eventId) {
		return jdbcTemplate.queryForObject("SELECT tableoid::regclass::text FROM events WHERE event_id = ?",
				String.class, eventId);
	}

	private int eventRowCount(String eventId) {
		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM events WHERE event_id = ?",
				Integer.class, eventId);
		return count == null ? 0 : count;
	}

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EVENT_PATH))
				.header("Content-Type", "application/json")
				.header("X-API-Key", API_KEY)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
		return "m11-" + UUID.randomUUID();
	}

}
