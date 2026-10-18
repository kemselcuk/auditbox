package io.auditbox.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * stable keyset pagination and negative cases. Requests authenticate with the static
 * X-API-Key of the test profile (application-test.yml, {@code ApiKeySecurityConfiguration});
 * the key is the D5 authentication mechanism since Stage 5.
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

	private static final String API_KEY = "test-api-key";

	/** Base instant of the seeding scenarios: whole minutes, so stored values are exact. */
	private static final Instant BASE = Instant.parse("2026-10-18T00:00:00Z");

	/**
	 * Page-count guard for the walk loops (review round 3, F7): together with the item-cap
	 * guard it makes a never-ending walk fail fast instead of hanging the test, without
	 * tying the expectation to the near-exact page arithmetic the walk assertions check.
	 */
	private static final int MAX_WALK_PAGES = 100;

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
			assertThat(pages).as("walked pages after page %d", pages)
					.isLessThanOrEqualTo(MAX_WALK_PAGES);
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
			assertThat(pages).as("walked pages after page %d", pages)
					.isLessThanOrEqualTo(MAX_WALK_PAGES);
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
		String farFutureCursor = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(
						"{\"occurredAt\":\"+294277-01-01T00:00:00Z\",\"eventId\":\"e\"}"
								.getBytes(StandardCharsets.UTF_8));
		return Stream.of(
				Arguments.of("cursor that is not base64url", "?cursor=!!!not-base64!!!"),
				Arguments.of("cursor that decodes to no JSON document", "?cursor=aGVsbG8"),
				Arguments.of("cursor with unexpected fields", "?cursor=" + jsonCursor),
				Arguments.of("cursor with an occurredAt outside the supported range",
						"?cursor=" + farFutureCursor),
				Arguments.of("blank cursor (a blank token, unlike blank filters, stays 400)",
						"?cursor="),
				Arguments.of("padded cursor", "?cursor=e30="),
				Arguments.of("size zero", "?size=0"),
				Arguments.of("size above the maximum", "?size=101"),
				Arguments.of("size not a number", "?size=abc"),
				Arguments.of("occurredFrom without an offset", "?occurredFrom=2026-10-18T12:00:00"),
				Arguments.of("occurredFrom that is not a date", "?occurredFrom=18-10-2026"),
				Arguments.of("occurredFrom outside the supported range",
						"?occurredFrom=%2B999999999-12-31T23:59:59Z"),
				Arguments.of("occurredTo without an offset", "?occurredTo=2026-10-18T12:00:00"),
				Arguments.of("occurredTo outside the supported range",
						"?occurredTo=-999999999-01-01T00:00:00Z"),
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
	// Review round 3: cursor worst case, range/NUL validation, F7 coverage
	// ---------------------------------------------------------------------

	@Test
	void walkWithTheEmittedCursorForAnEscapeHeavyMaximumLengthEventId() throws Exception {
		String user = unique("t4-escape-walk-user");
		// A write-side-legal eventId at 128 characters, made purely of control characters:
		// every one of them JSON-escapes to 6 characters inside the cursor, so this is the
		// worst case the encode bound must not refuse (review round 3, F1).
		String escapeHeavyId = "ctl-" + "\u0001".repeat(124);
		Seeded older = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES)));
		// The control characters must be JSON-escaped in transit; the service stores and
		// returns the real characters.
		String seedBody = "{\"eventId\":\"ctl-" + "\\u0001".repeat(124)
				+ "\",\"userId\":\"" + user + "\",\"eventType\":\"payment.settled\","
				+ "\"source\":\"core-banking\",\"occurredAt\":\"" + BASE.minus(10, ChronoUnit.MINUTES)
				+ "\"}";
		HttpResponse<String> seeded = post(seedBody);
		assertThat(seeded.statusCode()).as("seeding the escape-heavy event").isEqualTo(201);
		Seeded escapeHeavy = new Seeded(escapeHeavyId, user, "payment.settled", "core-banking",
				BASE.minus(10, ChronoUnit.MINUTES));

		Page firstPage = page(get("/api/v1/events?userId=" + user + "&size=1"));
		assertThat(firstPage.items()).extracting(Item::eventId).containsExactly(escapeHeavy.eventId());
		assertThat(firstPage.nextCursor()).as("cursor emitted for the escape-heavy id").isNotBlank();

		// The API must accept exactly the cursor it emitted: page two returns the older event.
		HttpResponse<String> secondResponse = get("/api/v1/events?userId=" + user
				+ "&size=1&cursor=" + firstPage.nextCursor());
		assertThat(secondResponse.statusCode())
				.as("the emitted nextCursor must decode despite JSON escaping")
				.isEqualTo(200);
		Page secondPage = page(secondResponse);
		assertThat(secondPage.items()).extracting(Item::eventId).containsExactly(older.eventId());
		assertThat(secondPage.nextCursor()).isNull();
	}

	@Test
	void occurredBoundsOutsideTheSupportedRangeAre400NamingTheParameter() throws Exception {
		// A parseable-but-unsupported year (integers up to +/-999999999 parse fine as
		// OffsetDateTime) must be a named-parameter 400, not a later 500 (review round 3, F4).
		assert400DetailNamesParameter(
				get("/api/v1/events?occurredFrom=%2B999999999-12-31T23:59:59Z"), "occurredFrom");
		assert400DetailNamesParameter(
				get("/api/v1/events?occurredTo=-999999999-01-01T00:00:00Z"), "occurredTo");
	}

	@Test
	void cursorOccurredAtOutsideTheSupportedRangeIs400NamingTheParameter() throws Exception {
		assert400DetailNamesParameter(
				get("/api/v1/events?cursor=" + cursorOf(
						"{\"occurredAt\":\"+294277-01-01T00:00:00Z\",\"eventId\":\"e\"}")),
				"occurredAt");
	}

	@Test
	void nulCharacterInFilterValuesIs400NamingTheParameter() throws Exception {
		assert400DetailNamesParameter(get("/api/v1/events?userId=user%00"), "userId");
		assert400DetailNamesParameter(get("/api/v1/events?eventType=payment%00x"), "eventType");
	}

	@Test
	void maximumPageSizeOf100IsAccepted() throws Exception {
		String user = unique("t4-maxsize-user");
		for (int i = 0; i < 3; i++) {
			seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
					BASE.minus(i, ChronoUnit.MINUTES)));
		}

		Page result = page(get("/api/v1/events?userId=" + user + "&size=100"));

		assertThat(result.items()).hasSize(3);
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void aPageLengthExactlyEqualToSizeEndsTheWalk() throws Exception {
		String user = unique("t4-fullpage-user");
		for (int i = 0; i < 4; i++) {
			seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
					BASE.minus(i, ChronoUnit.MINUTES)));
		}

		// Exactly `size` events: the page is full by construction, but no (size + 1)-th row
		// exists, so the walk must end here with nextCursor null (review round 3, F7).
		Page result = page(get("/api/v1/events?userId=" + user + "&size=4"));

		assertThat(result.items()).hasSize(4);
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void aTieGroupEndingOnTheLastPageHasNullNextCursor() throws Exception {
		String user = unique("t4-tailtie-user");
		String idPrefix = "t4tailtie-" + UUID.randomUUID();
		Seeded uniqueEarlier = seed(new Seeded(idPrefix + "-000", user, "payment.settled",
				"core-banking", BASE.minus(10, ChronoUnit.MINUTES)));
		Seeded tieFirst = seed(new Seeded(idPrefix + "-001", user, "payment.settled",
				"core-banking", BASE.minus(5, ChronoUnit.MINUTES)));
		Seeded tieLast = seed(new Seeded(idPrefix + "-002", user, "payment.settled",
				"core-banking", BASE.minus(5, ChronoUnit.MINUTES)));

		// The page ends exactly on a tie group: the keyset position would be ambiguous, but
		// with no (size + 1)-th row it must simply be the last page (review round 3, F7).
		Page result = page(get("/api/v1/events?userId=" + user + "&size=3"));

		assertThat(result.items()).extracting(Item::eventId)
				.containsExactly(tieLast.eventId(), tieFirst.eventId(), uniqueEarlier.eventId());
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void aCursorAtANonExistentPositionContinuesCorrectly() throws Exception {
		String user = unique("t4-gapcursor-user");
		Seeded oldest = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(30, ChronoUnit.MINUTES)));
		Seeded middle = seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(20, ChronoUnit.MINUTES)));
		seed(new Seeded(uniqueEventId(), user, "payment.settled", "core-banking",
				BASE.minus(10, ChronoUnit.MINUTES)));

		// A hand-made position exactly between two stored timestamps (no event at
		// BASE - 15min): the keyset predicate selects everything strictly before it.
		String betweenTimestamps = EventCursor.encode(
				OffsetDateTime.ofInstant(BASE.minus(15, ChronoUnit.MINUTES), ZoneOffset.UTC), "zzz");

		Page result = page(get("/api/v1/events?userId=" + user + "&size=20&cursor=" + betweenTimestamps));

		assertThat(result.items()).extracting(Item::eventId)
				.containsExactly(middle.eventId(), oldest.eventId());
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void blankFilterValuesAreTreatedAsAbsent() throws Exception {
		String user = unique("t4-blank-user");
		Seeded onlyUserEvent = seed(new Seeded(uniqueEventId(), user, "payment.settled",
				"core-banking", BASE.minus(30, ChronoUnit.MINUTES)));
		String otherUser = unique("t4-blank-user");
		seed(new Seeded(uniqueEventId(), otherUser, "payment.settled", "core-banking",
				BASE.minus(20, ChronoUnit.MINUTES)));

		// Blank values on every filter parameter are the same query as no parameters at all
		// (review round 3, F6). The database is shared by scenarios, so compare the two
		// requests against each other instead of against a fixed item universe.
		List<String> blankValues = page(get("/api/v1/events?userId=&eventType=&source=&size="))
				.items().stream().map(Item::eventId).toList();
		List<String> absentFilters = page(get("/api/v1/events"))
				.items().stream().map(Item::eventId).toList();

		assertThat(blankValues).isEqualTo(absentFilters);
		// The user's own event is in the result even with all filter parameters present but
		// blank: a blank value never narrowed anything to the empty string.
		assertThat(blankValues).contains(onlyUserEvent.eventId());
	}

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
				.header("X-API-Key", API_KEY)
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
				.header("X-API-Key", API_KEY)
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

	/** Base64url-without-padding encoding of a raw cursor JSON document, for negative tests. */
	private static String cursorOf(String json) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 400 Problem Details whose detail names the offending parameter (review round 3, F4):
	 * GET parameter errors must never surface the POST-oriented "Event content could not be
	 * stored" detail.
	 */
	private static void assert400DetailNamesParameter(HttpResponse<String> response, String parameter)
			throws Exception {
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Bad Request");
		assertThat(problem.get("detail").stringValue()).contains(parameter);
		assertThat(problem.get("detail").stringValue()).doesNotContain("Event content could not be stored");
	}
}
