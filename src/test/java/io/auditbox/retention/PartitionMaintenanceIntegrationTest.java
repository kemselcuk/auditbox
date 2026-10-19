package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.auditbox.metrics.AuditMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * M11.2 integration tests (decision D22, Testcontainers PostgreSQL 18 like the other
 * integration classes) with the job driven through an injected FIXED clock — no sleeps
 * and no wall clock inside scenario decisions:
 *
 * <ul>
 *   <li>order 1 proves the STARTUP pass really ran once (it is the only actor before the
 *       first test): it created the four lookahead partitions and dropped the nine
 *       fully-expired partitions of the V2 initial window,</li>
 *   <li>ensure: a missing lookahead partition is created with the exact
 *       {@code [month start UTC, next month start UTC)} bounds; a second unchanged pass
 *       creates, drops and deletes nothing (idempotent),</li>
 *   <li>drop rules: a fully expired partition is dropped, a partition whose range
 *       contains the cutoff is kept, and a partition whose upper bound is EXACTLY the
 *       cutoff follows the documented {@code upper <= cutoff} rule (dropped),</li>
 *   <li>bounded DEFAULT cleanup: the batch cap, the stop on a not-full batch, and rows
 *       at/after the cutoff surviving,</li>
 *   <li>the three retention counters via before/after deltas.</li>
 * </ul>
 *
 * <p>Every scenario pins the shared fixed clock to its own instant first and asserts
 * deltas over the partitions/rows it itself set up; the scenario dates are built
 * relative to the real current month, so the class stays valid in any year.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"auditbox.retention.enabled=true",
		"auditbox.retention.months=24",
		"auditbox.retention.lookahead-months=3",
		"auditbox.retention.default-delete-batch-size=100",
		"auditbox.retention.max-batches-per-run=3" })
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class PartitionMaintenanceIntegrationTest {

	/** Pinned PostgreSQL image; keep in sync with docker-compose.yml. */
	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	/** The lookahead window of these scenarios (pinned via the class properties). */
	private static final int LOOKAHEAD = 3;

	/** The real current month (once, at class load); all scenario months derive from it. */
	private static final YearMonth CURRENT = YearMonth.now(ZoneOffset.UTC);

	/**
	 * The base clock instant of the scenarios: real current month + 8, day 15, 10:00 UTC.
	 * Relative to reality rather than a hardcoded year: the V2 initial window
	 * (real current − 25 .. + 3, built with the real clock at boot) then ends five months
	 * before the fake "current" month, so the startup ensure window (fake current .. +3)
	 * finds exactly four months missing — and the fixed month is deep inside the future.
	 */
	static final Instant INITIAL_INSTANT = scenarioClock(8, 15);

	/** Matches bounds like FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00'). */
	private static final Pattern BOUND_PATTERN = Pattern
			.compile("FOR VALUES FROM \\('([^']+)'\\) TO \\('([^']+)'\\)");

	@Autowired
	PartitionMaintenanceService service;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MeterRegistry meterRegistry;

	@Autowired
	TestClock testClock;

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		/**
		 * Fixed clock replacing the production UTC clock bean; primary so the service
		 * wires this one. Mutable so ONE shared context can serve every scenario — each
		 * test pins the shared clock to its own fixed instant before acting.
		 */
		@Bean
		@Primary
		TestClock fixedClock() {
			return new TestClock(INITIAL_INSTANT);
		}

	}

	/** Fixed clock whose instant the tests set before a scenario; getZone is UTC. */
	static final class TestClock extends Clock {

		private volatile Instant instant;

		private TestClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant newInstant) {
			this.instant = newInstant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

	}

	// ---------------------------------------------------------------------
	// 1. The startup pass
	// ---------------------------------------------------------------------

	/**
	 * The startup pass ran ONCE, after Flyway, with the fixed clock — no test has run
	 * anything yet. It created the four lookahead partitions (with exact bounds), kept
	 * the twenty partitions overlapping the retention window, dropped the nine fully
	 * expired ones, and left the DEFAULT partition in place; the fresh counters show
	 * exactly that work.
	 */
	@Test
	@Order(1)
	void theStartupPassRunsOnceAfterFlywayWithTheFixedClock() throws Exception {
		YearMonth fakeCurrent = yearMonthOf(INITIAL_INSTANT);
		assertThat(fakeCurrent).isEqualTo(CURRENT.plusMonths(8));
		// Startup cutoff = fakeNow - 24 months, preserving the fake day and time.
		Instant startupCutoff = scenarioClock(-16, 15);

		// The fixed clock's lookahead window exists, with the exact UTC bounds.
		List<String> partitions = monthlyPartitionNames();
		assertThat(partitions).hasSize(24); // 20 V2 keepers + 4 created at startup
		for (int offset = 0; offset <= LOOKAHEAD; offset++) {
			String name = partitionName(CURRENT.plusMonths(offset));
			assertThat(partitions).contains(name);
			assertPartitionBounds(name, CURRENT.plusMonths(offset));
		}
		// Nothing beyond the ensured window (the next month was not created yet).
		assertThat(partitions).doesNotContain(partitionName(CURRENT.plusMonths(LOOKAHEAD + 1)));

		// Drop rule at the startup clock: every fully expired V2 partition is gone; each
		// partition overlapping the retention window survives.
		int expired = 0;
		int surviving = 0;
		for (int offset = -25; offset <= 3; offset++) {
			YearMonth month = CURRENT.plusMonths(offset);
			if (partitionUpperBound(month).isAfter(startupCutoff)) {
				assertThat(partitions).contains(partitionName(month));
				surviving++;
			}
			else {
				assertThat(partitions).doesNotContain(partitionName(month));
				expired++;
			}
		}
		assertThat(surviving).isEqualTo(20);
		assertThat(expired).isEqualTo(9);
		assertThat(partitionNames()).contains("events_default");

		// Fresh context: ONLY the startup pass incremented — exact values.
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(LOOKAHEAD + 1);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(expired);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(0);
	}

	// ---------------------------------------------------------------------
	// 2. Ensure the lookahead window
	// ---------------------------------------------------------------------

	/**
	 * A missing lookahead partition is created with the exact bounds and existing ones
	 * are not recreated: running the unchanged pass a second time does absolutely
	 * nothing and leaves the partition set untouched.
	 */
	@Test
	@Order(2)
	void aMissingLookaheadPartitionIsCreatedWithExactBoundsAndThePassIsIdempotent() {
		// Four months past the startup clock: months +12..+15 are missing; the cutoff
		// slid forward enough to expire the V2 keepers current-16 .. current-13.
		testClock.set(scenarioClock(12, 15));

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);
		double deletedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED);
		List<String> before = partitionNames();

		PartitionMaintenanceService.MaintenanceSummary first = service.runMaintenance();

		// Exactly the four missing lookahead months ... with the exact UTC bounds.
		assertThat(first.partitionsCreated()).isEqualTo(4);
		List<String> after = partitionNames();
		assertThat(monthlyPartitionNames()).hasSize(before.size() - 1 /* default */);
		for (int offset = 12; offset <= 15; offset++) {
			YearMonth month = CURRENT.plusMonths(offset);
			assertThat(after).contains(partitionName(month));
			assertPartitionBounds(partitionName(month), month);
		}
		// ... the same pass's expired slice ... (current-16 .. current-13), and the
		// exactly-adjacent month current-12 survives.
		assertThat(first.partitionsDropped()).isEqualTo(4);
		assertThat(after).doesNotContain(partitionName(CURRENT.plusMonths(-16)),
				partitionName(CURRENT.plusMonths(-13)));
		assertThat(after).contains(partitionName(CURRENT.plusMonths(-12)));
		assertThat(first.defaultRowsDeleted()).isEqualTo(0);

		// Counter deltas of the first pass.
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 4);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 4);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore);

		// Idempotency: the unchanged second pass reports a zero summary, touches no
		// counter and leaves the partition set exactly as the first pass left it.
		List<String> beforeSecond = partitionNames();
		PartitionMaintenanceService.MaintenanceSummary second = service.runMaintenance();
		assertThat(second).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 0));
		assertThat(partitionNames()).isEqualTo(beforeSecond);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 4);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 4);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore);
	}

	// ---------------------------------------------------------------------
	// 3. Retention drop rules
	// ---------------------------------------------------------------------

	@Test
	@Order(3)
	void aFullyExpiredPartitionIsDroppedAndAPartitionContainingTheCutoffIsKept() {
		// Back at the startup clock, the automated work is quiescent: everything
		// droppable is already gone, so the pass's only work comes from the partitions
		// this test creates — whose REAL catalog bounds drive the rule, not the name.
		testClock.set(scenarioClock(8, 15));

		createMonthlyPartition(CURRENT.plusMonths(-20)); // wholly before the cutoff
		createMonthlyPartition(CURRENT.plusMonths(-16)); // its range CONTAINS the cutoff
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(-20)),
				partitionName(CURRENT.plusMonths(-16)));

		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);
		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double deletedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 0));
		assertThat(monthlyPartitionNames()).doesNotContain(partitionName(CURRENT.plusMonths(-20)));
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(-16)));
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 1);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore);
	}

	@Test
	@Order(4)
	void aPartitionWhoseUpperBoundIsExactlyTheCutoffIsDroppedPerTheDocumentedRule() {
		// Clock at a month start, so the cutoff (fake now - 24 months) is exactly a month
		// start: current-17's range ends precisely at the cutoff instant.
		Instant cutoff = CURRENT.plusMonths(-16).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		testClock.set(CURRENT.plusMonths(8).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());

		createMonthlyPartition(CURRENT.plusMonths(-17));
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(-17)));

		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);
		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		// upper == cutoff → droppable per the documented <= rule: the partition is gone.
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 0));
		assertThat(monthlyPartitionNames()).doesNotContain(partitionName(CURRENT.plusMonths(-17)));
		// The neighbor whose upper bound lies one instant past the cutoff is kept.
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(-16)));
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 1);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore);
	}

	// ---------------------------------------------------------------------
	// 4. Bounded DEFAULT-partition cleanup
	// ---------------------------------------------------------------------

	@Test
	@Order(5)
	void defaultRowsOlderThanTheCutoffAreDeletedInBoundedBatchesAndNewerOnesSurvive() {
		// Clock one month past the startup pass: quiescent ensure (+9..+12 all exist),
		// and the cutoff month (current-15) carries NO monthly partition, so the keep
		// rows below genuinely live in the DEFAULT partition. The pass drops exactly
		// one partition (current-16, created and kept in order 3) whose slice is now
		// fully expired at this clock.
		testClock.set(scenarioClock(9, 15));
		OffsetDateTime cutoff = CURRENT.plusMonths(-15).atDay(15).atTime(10, 0).atOffset(ZoneOffset.UTC);

		// 350 old rows: 3 full batches (the per-pass cap) + a 50-row remainder.
		insertRows(350, startOfDayUtc(CURRENT.plusMonths(-20), 1), "retention-del-");
		int oldBefore = countEvents("retention-del-%");
		assertThat(oldBefore).isEqualTo(350);

		// Kept rows, both routed to the DEFAULT partition (no monthly partition covers
		// their occurredAt): one EXACTLY at the cutoff (the delete is strictly `<`) and
		// one after it.
		assertThat(insertEvent("retention-keep-at-cutoff", cutoff)).isEqualTo(1);
		assertThat(insertEvent("retention-keep-after-cutoff",
				startOfDayUtc(CURRENT.plusMonths(30), 1))).isEqualTo(1);

		double deletedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED);
		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);

		// Pass 1: 100 + 100 + 100 — the 3-batch cap binds while rows remain.
		PartitionMaintenanceService.MaintenanceSummary firstPass = service.runMaintenance();
		assertThat(firstPass).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 300));
		assertThat(countEvents("retention-del-%")).isEqualTo(oldBefore - 300);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore + 300);

		// Pass 2: 50 < batch size -> one short batch, then the loop stops.
		PartitionMaintenanceService.MaintenanceSummary secondPass = service.runMaintenance();
		assertThat(secondPass).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 50));
		assertThat(countEvents("retention-del-%")).isEqualTo(0);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore + 350);

		// Rows at and after the cutoff survive every pass, still in the DEFAULT
		// partition; ensure stayed quiescent (zero created either pass).
		assertThat(countEvents("retention-keep-%")).isEqualTo(2);
		assertThat(routingPartitionOf("retention-keep-at-cutoff")).isEqualTo("events_default");
		assertThat(routingPartitionOf("retention-keep-after-cutoff")).isEqualTo("events_default");
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 1);

		jdbcTemplate.update("DELETE FROM events WHERE event_id LIKE 'retention-%'");
	}

	// ---------------------------------------------------------------------
	// 5. Metrics
	// ---------------------------------------------------------------------

	/**
	 * The three untagged counters move exactly with one pass of each kind of work: one
	 * recreated lookahead partition, one dropped expired partition, two deleted DEFAULT
	 * rows — all asserted as before/after deltas against the shared registry.
	 */
	@Test
	@Order(6)
	void theThreeRetentionCountersMoveExactlyWithOnePassOfEachKindOfWork() {
		testClock.set(scenarioClock(8, 15));

		// Arrange all three kinds of work: a momentarily missing lookahead partition
		// (dropped by hand), a fully expired partition, two old DEFAULT rows.
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(11)));
		createMonthlyPartition(CURRENT.plusMonths(-17));
		insertRows(2, startOfDayUtc(CURRENT.plusMonths(-20), 1), "retention-metrics-");

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);
		double deletedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED);

		service.runMaintenance();

		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 1);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore + 1);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore + 2);
		// The recreated partition carries the exact standard bounds again.
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(11)));
		assertPartitionBounds(partitionName(CURRENT.plusMonths(11)), CURRENT.plusMonths(11));
		assertThat(monthlyPartitionNames()).doesNotContain(partitionName(CURRENT.plusMonths(-17)));
		assertThat(countEvents("retention-metrics-%")).isEqualTo(0);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private double count(String name) {
		Counter counter = meterRegistry.find(name).counter();
		return counter == null ? 0.0 : counter.count();
	}

	/** Scenario clock instant: (real current month + offset), day, 10:00 UTC. */
	private static Instant scenarioClock(int offset, int day) {
		return CURRENT.plusMonths(offset).atDay(day).atTime(10, 0).atOffset(ZoneOffset.UTC).toInstant();
	}

	/** Start of a day as an UTC OffsetDateTime (an insert/CUTOFF value for the DB). */
	private static OffsetDateTime startOfDayUtc(YearMonth month, int day) {
		return month.atDay(day).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
	}

	private static YearMonth yearMonthOf(Instant instant) {
		return YearMonth.from(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
	}

	private static Instant partitionUpperBound(YearMonth month) {
		return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	private static String partitionName(YearMonth month) {
		return "events_" + DateTimeFormatter.ofPattern("uuuu_MM", Locale.ROOT).format(month);
	}

	/** All direct child partitions of events (monthly names and the default). */
	private List<String> partitionNames() {
		return jdbcTemplate.queryForList("""
				SELECT c.relname FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				WHERE i.inhparent = 'public.events'::regclass""", String.class);
	}

	private List<String> monthlyPartitionNames() {
		List<String> monthly = new ArrayList<>();
		for (String name : partitionNames()) {
			if (name.matches("events_\\d{4}_\\d{2}")) {
				monthly.add(name);
			}
		}
		return monthly;
	}

	private void assertPartitionBounds(String partitionName, YearMonth month) {
		String bound = jdbcTemplate.queryForObject("""
				SELECT pg_get_expr(c.relpartbound, c.oid)
				FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				WHERE i.inhparent = 'public.events'::regclass AND c.relname = ?""",
				String.class, partitionName);
		Matcher matcher = BOUND_PATTERN.matcher(bound);
		assertThat(matcher.matches()).as("bound expression of " + partitionName + ": " + bound).isTrue();
		assertThat(parseTimestampLiteral(matcher.group(1)))
				.isEqualTo(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
		assertThat(parseTimestampLiteral(matcher.group(2)))
				.isEqualTo(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	/** Parses a PostgreSQL timestamp literal like '2026-10-01 00:00:00+00' to its instant. */
	private static Instant parseTimestampLiteral(String literal) {
		String iso = literal.replace(' ', 'T');
		if (iso.matches(".*[+-]\\d{2}$")) {
			iso = iso + ":00";
		}
		return OffsetDateTime.parse(iso).toInstant();
	}

	/** Creates a monthly partition with the standard month-aligned UTC bounds. */
	private void createMonthlyPartition(YearMonth month) {
		String lower = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
				.format(month.atDay(1).atStartOfDay()) + "+00";
		String upper = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
				.format(month.plusMonths(1).atDay(1).atStartOfDay()) + "+00";
		jdbcTemplate.execute("CREATE TABLE " + partitionName(month)
				+ " PARTITION OF events FOR VALUES FROM ('" + lower + "') TO ('" + upper + "')");
	}

	/** Inserts one row that routes wherever its occurredAt dictates; returns row count. */
	private int insertEvent(String eventId, OffsetDateTime occurredAt) {
		return jdbcTemplate.update("""
				INSERT INTO events (event_id, user_id, event_type, source, occurred_at, content_hash)
				VALUES (?, 'user-retention', 'payment.settled', 'retention-test', ?, 'retention-hash')""",
				eventId, occurredAt);
	}

	/** Bulk-inserts rows with identical occurredAt that route into the DEFAULT partition. */
	private void insertRows(int count, OffsetDateTime occurredAt, String eventIdPrefix) {
		jdbcTemplate.update("""
				INSERT INTO events (event_id, user_id, event_type, source, occurred_at, content_hash)
				SELECT ? || i, 'user-retention', 'payment.settled', 'retention-test', ?, 'retention-hash'
				FROM generate_series(1, ?) AS i""",
				eventIdPrefix, occurredAt, count);
	}

	private int countEvents(String eventIdLike) {
		Integer rows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM events WHERE event_id LIKE ?", Integer.class, eventIdLike);
		return rows == null ? 0 : rows;
	}

	private String routingPartitionOf(String eventId) {
		return jdbcTemplate.queryForObject(
				"SELECT tableoid::regclass::text FROM events WHERE event_id = ?", String.class, eventId);
	}

}
