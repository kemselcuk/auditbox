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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManagerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4 integration tests (plan tasks T4.1/T4.2) against a real PostgreSQL (Testcontainers,
 * same pinned image as docker-compose.yml) and real HTTP: GET /api/v1/events filters,
 * stable keyset pagination and negative cases. Requests authenticate with the temporary
 * basic-auth credentials of the test profile (application-test.yml,
 * {@code TemporarySecurityConfiguration}); Stage 5 replaces this mechanism with the
 * X-API-Key filter (D5).
 *
 * <p>Determinism: every scenario seeds its own events identified by a unique userId (and
 * unique eventIds), so the tests are independent of each other and of execution order
 * despite sharing one database.
 *
 * <p>{@code generate_statistics} is enabled so the one-SELECT-per-page guarantee of T4.1
 * (no COUNT query, decision D3) is asserted with Hibernate statement counters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
class EventListApiControllerTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
			.encodeToString("test-user:test-password".getBytes(StandardCharsets.UTF_8));

	/** Base instant of the seeding scenarios: whole minutes, so stored values are exact. */
	private static final Instant BASE = Instant.parse("2026-10-18T00:00:00Z");

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
	EntityManagerFactory entityManagerFactory;

	private final HttpClient client = HttpClient.newHttpClient();

	/** A seeded event, with the instant the API must return for its occurredAt (Z-form input). */
	private record Seeded(String eventId, String userId, String eventType, String source, Instant occurredAt) {
	}

	/** One {@code items} entry of a page response, reduced to the fields the tests assert on. */
	private record Item(String eventId, String userId, String eventType, String source, Instant occurredAt) {
	}

	private record Page(List<Item> items, String nextCursor) {
	}

	/** The API contract order (T4.1, D3): occurredAt DESC, eventId DESC. */
	private static final Comparator<Seeded> KEYSET_ORDER =
			Comparator.comparing(Seeded::occurredAt).reversed()
					.thenComparing(Seeded::eventId, Comparator.reverseOrder());

	private static final Comparator<Item> RESPONSE_KEYSET_ORDER =
			Comparator.comparing(Item::occurredAt).reversed()
					.thenComparing(Item::eventId, Comparator.reverseOrder());

	// ---------------------------------------------------------------------
	// T4.2 acceptance 1: filters alone and combined, inclusive bounds
	// ---------------------------------------------------------------------

	@Test
	void filterByUserIdReturnsOnlyThatUsersEventsNewestFirst() throws Exception {
		String user = unique("t4-filter-user");
		String otherUser = unique("t4-filter-user");
		Seeded earlier = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(60, ChronoUnit.MINUTES)));
		seed(new Seeded(uniqueEventId(), otherUser, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES)));
		Seeded later = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(10, ChronoUnit.MINUTES)));

		Page result = page(get("/api/v1/events?userId=" + user));

		assertThat(result.items()).extracting(Item::eventId)
				.containsExactly(later.eventId(), earlier.eventId());
		assertThat(result.items()).allSatisfy(item -> assertThat(item.userId()).isEqualTo(user));
		// Fewer than the default page size of 20: the walk ends after this page.
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void filterByEventTypeReturnsOnlyMatchingEvents() throws Exception {
		String user = unique("t4-filter-type");
		Seeded settled = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(50, ChronoUnit.MINUTES)));
		seed(new Seeded(uniqueEventId(), user, "payment.refunded", "core-banking",
				BASE.minus(40, ChronoUnit.MINUTES)));
		Seeded secondSettled = seed(new Seeded(uniqueEventId(), user, "payment.settled", "card-gateway",
				BASE.minus(30, ChronoUnit.MINUTES)));

		Page result = page(get("/api/v1/events?userId=" + user + "&eventType=payment.settled"));

		assertThat(result.items()).extracting(Item::eventId)
				.containsExactly(secondSettled.eventId(), settled.eventId());
	}

	@Test
	void filterBySourceReturnsOnlyMatchingEvents() throws Exception {
		String user = unique("t4-filter-source");
		Seeded coreBanking = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(50, ChronoUnit.MINUTES)));
		seed(new Seeded(uniqueEventId(), user, "payment.settled", "card-gateway",
				BASE.minus(40, ChronoUnit.MINUTES)));

		Page result = page(get("/api/v1/events?userId=" + user + "&source=core-banking"));

		assertThat(result.items()).extracting(Item::eventId).containsExactly(coreBanking.eventId());
	}

	@Test
	void occurredAtRangeBoundariesAreInclusiveAndAcceptOffsets() throws Exception {
		String user = unique("t4-filter-range");
		Seeded oneHourBefore = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(60, ChronoUnit.MINUTES)));
		Seeded atTheBoundary = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES)));
		Seeded latest = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(0, ChronoUnit.MINUTES)));

		// from == to pins exactly the event at that instant: both bounds are inclusive.
		Page pointInTime = page(get("/api/v1/events?userId=" + user
				+ "&occurredFrom=2026-10-17T23:30:00Z&occurredTo=2026-10-17T23:30:00Z"));
		assertThat(pointInTime.items()).extracting(Item::eventId).containsExactly(atTheBoundary.eventId());

		// A non-UTC offset must be accepted (same strictness as occurredAt, D10): the same
		// instant spelled as 00:30+01:00 (URL-encoded plus) selects the same event.
		Page withOffset = page(get("/api/v1/events?userId=" + user
				+ "&occurredFrom=2026-10-18T00:30:00%2B01:00&occurredTo=2026-10-18T00:30:00%2B01:00"));
		assertThat(withOffset.items()).extracting(Item::eventId).containsExactly(atTheBoundary.eventId());

		// The full range spanning all seeds returns all of them, bounds included.
		Page everything = page(get("/api/v1/events?userId=" + user
				+ "&occurredFrom=2026-10-17T23:00:00Z&occurredTo=2026-10-18T00:00:00Z"));
		assertThat(everything.items()).extracting(Item::eventId)
				.containsExactly(latest.eventId(), atTheBoundary.eventId(), oneHourBefore.eventId());

		// A range between the seeds excludes the events outside it.
		Page narrowed = page(get("/api/v1/events?userId=" + user
				+ "&occurredFrom=2026-10-17T23:15:00Z&occurredTo=2026-10-17T23:45:00Z"));
		assertThat(narrowed.items()).extracting(Item::eventId).containsExactly(atTheBoundary.eventId());
	}

	@Test
	void combinedFiltersApplyConjunctively() throws Exception {
		String user = unique("t4-filter-combined");
		String otherUser = unique("t4-filter-combined");
		seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(40, ChronoUnit.MINUTES))); // outside the date range
		Seeded matching = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES)));
		seed(new Seeded(uniqueEventId(), user, "payment.refunded", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES))); // wrong eventType
		seed(new Seeded(uniqueEventId(), user, "payment.settled", "card-gateway",
				BASE.minus(30, ChronoUnit.MINUTES))); // wrong source
		seed(new Seeded(uniqueEventId(), otherUser, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES))); // wrong userId

		Page result = page(get("/api/v1/events?userId=" + user + "&eventType=payment.settled&source=core-banking"
				+ "&occurredFrom=2026-10-17T23:25:00Z&occurredTo=2026-10-17T23:55:00Z"));

		assertThat(result.items()).extracting(Item::eventId).containsExactly(matching.eventId());
	}

	@Test
	void noMatchingFilterReturnsAnEmptyPage() throws Exception {
		String user = unique("t4-filter-nobody");

		HttpResponse<String> response = get("/api/v1/events?userId=" + user);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		Page result = page(response);
		assertThat(result.items()).isEmpty();
		assertThat(result.nextCursor()).isNull();
	}

	// ---------------------------------------------------------------------
	// T4.2 acceptance 2: pagination walk without gaps or duplicates
	// ---------------------------------------------------------------------

	@Test
	void paginationWalkTraversesAllEventsWithoutGapsOrDuplicates() throws Exception {
		String user = unique("t4-walk-user");
		String idPrefix = "t4walk-" + UUID.randomUUID();
		List<Seeded> seeded = new ArrayList<>();
		for (int i = 0; i < 45; i++) {
			// Distinct minutes, except i = 9, 10 and 11 share one occurredAt. With page size
			// 10 the first page boundary (after the 10th item) cuts through that tie group,
			// so the walk must resume exactly on the event_id tiebreak of the keyset
			// predicate. The zero-padded eventIds order identically under byte and locale
			// collations, keeping the expected sequence unambiguous.
			Instant occurredAt = BASE.minus(i >= 10 && i <= 11 ? 9 : i, ChronoUnit.MINUTES);
			Seeded event = new Seeded(idPrefix + String.format("-%03d", i), user, "payment.settled",
					"core-banking", occurredAt);
			seed(event);
			seeded.add(event);
		}
		List<String> expectedOrder = seeded.stream().sorted(KEYSET_ORDER).map(Seeded::eventId).toList();

		List<Item> walked = new ArrayList<>();
		List<String> nextCursors = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			HttpResponse<String> response = get("/api/v1/events?userId=" + user + "&size=10"
					+ (cursor == null ? "" : "&cursor=" + cursor));
			assertThat(response.statusCode()).isEqualTo(200);
			Page current = page(response);
			walked.addAll(current.items());
			nextCursors.add(current.nextCursor());
			cursor = current.nextCursor();
			pages++;
			// Guard against a walk that never terminates: the walk must stay within the
			// seeded universe (and the test would fail on duplicates below anyway).
			assertThat(walked.size()).as("walked items after page %d", pages).isLessThanOrEqualTo(45);
		}
		while (cursor != null);

		// 45 events at 10 per page: 4 continuation cursors, the 5th page ends the walk.
		assertThat(pages).isEqualTo(5);
		assertThat(nextCursors.subList(0, 4)).allSatisfy(next -> assertThat(next).isNotBlank());
		assertThat(nextCursors.getLast()).isNull();

		assertThat(walked).hasSize(45);
		assertThat(walked).extracting(Item::eventId).doesNotHaveDuplicates();
		assertThat(walked).isSortedAccordingTo(RESPONSE_KEYSET_ORDER);
		// The full walk is exactly the expected sequence: no gaps, no reorderings.
		assertThat(walked).extracting(Item::eventId).containsExactlyElementsOf(expectedOrder);
	}

	// ---------------------------------------------------------------------
	// T4.2 acceptance 3: stability under a concurrent insert
	// ---------------------------------------------------------------------

	@Test
	void cursorWalkIgnoresAnEventInsertedBetweenPages() throws Exception {
		String user = unique("t4-stable-user");
		List<Seeded> seeded = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			Seeded event = new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
					BASE.minus(i, ChronoUnit.MINUTES));
			seed(event);
			seeded.add(event);
		}
		List<String> expectedOrder = seeded.stream().sorted(KEYSET_ORDER).map(Seeded::eventId).toList();

		// Page 1 with the original data...
		Page firstPage = page(get("/api/v1/events?userId=" + user + "&size=10"));
		assertThat(firstPage.items()).hasSize(10);
		assertThat(firstPage.nextCursor()).isNotBlank();

		// ...a NEWER event is inserted between pages: offset pagination would shift
		// everything and re-serve the first remaining row; keyset must not.
		Seeded newcomer = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.plus(60, ChronoUnit.MINUTES)));

		// The walk continues with the OLD cursor...
		List<Item> walked = new ArrayList<>(firstPage.items());
		String cursor = firstPage.nextCursor();
		int pages = 1;
		while (cursor != null) {
			Page current = page(get("/api/v1/events?userId=" + user + "&size=10&cursor=" + cursor));
			walked.addAll(current.items());
			cursor = current.nextCursor();
			pages++;
			// Guard against a walk that never terminates (same as in the walk test).
			assertThat(walked.size()).as("walked items after page %d", pages).isLessThanOrEqualTo(30);
		}

		assertThat(pages).isEqualTo(3);
		// ...and the remainder is exactly the original remainder: no duplicates, no gaps,
		// no injected newcomer.
		List<String> walkedIds = walked.stream().map(Item::eventId).toList();
		List<String> expectedIds = new ArrayList<>(
				firstPage.items().stream().map(Item::eventId).toList());
		expectedOrder.stream().skip(10).forEach(eventId -> expectedIds.add(eventId));
		assertThat(walkedIds).containsExactlyElementsOf(expectedIds);
		assertThat(walkedIds).doesNotContain(newcomer.eventId());
		assertThat(walkedIds).doesNotHaveDuplicates();

		// A fresh walk (new cursor chain) does include the newcomer, at the head.
		Page freshFirstPage = page(get("/api/v1/events?userId=" + user + "&size=10"));
		assertThat(freshFirstPage.items().getFirst().eventId()).isEqualTo(newcomer.eventId());
	}

	// ---------------------------------------------------------------------
	// T4.2 acceptance 4: negative cases
	// ---------------------------------------------------------------------

	static Stream<Arguments> invalidQueries() {
		String jsonCursor = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"foo\":\"bar\"}".getBytes(StandardCharsets.UTF_8));
		return Stream.of(
				Arguments.of("cursor that is not base64url", "?cursor=!!!not-base64!!!"),
				Arguments.of("cursor that decodes to no JSON document", "?cursor=aGVsbG8"),
				Arguments.of("cursor with unexpected fields", "?cursor=" + jsonCursor),
				Arguments.of("padded cursor", "?cursor=e30="),
				Arguments.of("size zero", "?size=0"),
				Arguments.of("size above the maximum", "?size=101"),
				Arguments.of("size not a number", "?size=abc"),
				Arguments.of("occurredFrom without an offset", "?occurredFrom=2026-10-18T12:00:00"),
				Arguments.of("occurredFrom that is not a date", "?occurredFrom=18-10-2026"),
				Arguments.of("occurredTo without an offset", "?occurredTo=2026-10-18T12:00:00"),
				Arguments.of("occurredFrom after occurredTo",
						"?occurredFrom=2026-10-18T12:00:01Z&occurredTo=2026-10-18T12:00:00Z"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidQueries")
	void invalidQueryParametersReturn400ProblemDetails(String label, String query) throws Exception {
		HttpResponse<String> response = get("/api/v1/events" + query);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("status").intValue()).isEqualTo(400);
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(problem.get("detail").stringValue()).isNotBlank();
	}

	// ---------------------------------------------------------------------
	// T4.1 acceptance: exactly one SELECT per page request, no COUNT
	// ---------------------------------------------------------------------

	@Test
	void aPageRequestIssuesExactlyOneSelectWithoutCount() throws Exception {
		String user = unique("t4-stats-user");
		String idPrefix = "t4stats-" + UUID.randomUUID();
		for (int i = 0; i < 12; i++) {
			seed(new Seeded(idPrefix + "-" + i, user, "payment.settled", "core-banking",
					BASE.minus(i, ChronoUnit.MINUTES)));
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

		statistics.clear();
		Page firstPage = page(get("/api/v1/events?userId=" + user + "&size=5"));

		// Exactly one prepared statement per page request: the page SELECT itself — no
		// COUNT query for a total (D3). nextCursor proves "more results exist" came from
		// the fetched (size + 1)-th row, not from an extra query.
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(firstPage.items()).hasSize(5);
		assertThat(firstPage.nextCursor()).isNotBlank();

		statistics.clear();
		page(get("/api/v1/events?userId=" + user + "&size=5&cursor=" + firstPage.nextCursor()));

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	// ---------------------------------------------------------------------
	// T4.2 acceptance 6: OpenAPI documentation
	// ---------------------------------------------------------------------

	@Test
	void openApiDocsDescribeTheListEndpoint() throws Exception {
		HttpResponse<String> response = get("/v3/api-docs");

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode docs = JSON.readTree(response.body());

		JsonNode listOperation = docs.get("paths").get("/api/v1/events").get("get");
		assertThat(listOperation).isNotNull();
		assertThat(listOperation.get("summary").stringValue()).isEqualTo("List events");
		List<String> parameterNames = new ArrayList<>();
		listOperation.get("parameters").forEach(parameter -> parameterNames.add(parameter.get("name").asString()));
		assertThat(parameterNames).containsExactlyInAnyOrder(
				"userId", "eventType", "source", "occurredFrom", "occurredTo", "cursor", "size");

		JsonNode responses = listOperation.get("responses");
		assertThat(responses.has("200")).isTrue();
		assertThat(responses.has("400")).isTrue();
		assertThat(responses.get("200").get("content").get("application/json").get("schema")
				.get("$ref").stringValue()).endsWith("EventPage");
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private HttpResponse<String> get(String pathAndQuery) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
				.header("Authorization", BASIC_AUTH)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Posts one event over real HTTP (the same write path as Stage 3) and asserts 201. */
	private Seeded seed(Seeded event) throws Exception {
		HttpResponse<String> response = post(eventJson(event.eventId(), event.userId(), event.eventType(),
				event.source(), event.occurredAt().toString()));
		assertThat(response.statusCode())
				.as("seeding event %s", event.eventId())
				.isEqualTo(201);
		return event;
	}

	private HttpResponse<String> post(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header("Content-Type", "application/json")
				.header("Authorization", BASIC_AUTH)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Builds a request body; the GET-test seeds carry no metadata. */
	private static String eventJson(String eventId, String userId, String eventType, String source,
			String occurredAt) {
		return "{\"eventId\":\"" + eventId + "\",\"userId\":\"" + userId + "\",\"eventType\":\"" + eventType
				+ "\",\"source\":\"" + source + "\",\"occurredAt\":\"" + occurredAt + "\"}";
	}

	/** Parses a 200 page body into the envelope the API contract defines (T4.1). */
	private static Page page(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = JSON.readTree(response.body());
		List<Item> items = new ArrayList<>();
		body.get("items").forEach(item -> items.add(new Item(
				item.get("eventId").asString(),
				item.get("userId").asString(),
				item.get("eventType").asString(),
				item.get("source").asString(),
				OffsetDateTime.parse(item.get("occurredAt").asString()).toInstant())));
		JsonNode nextCursor = body.get("nextCursor");
		return new Page(items, nextCursor.isNull() ? null : nextCursor.asString());
	}

	private static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static String uniqueEventId() {
		return "t4-" + UUID.randomUUID();
	}
}
