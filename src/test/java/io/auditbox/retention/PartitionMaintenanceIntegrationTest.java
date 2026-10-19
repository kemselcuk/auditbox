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
 *   <li>the retention counters via before/after deltas,</li>
 *   <li>review F2: a month whose range holds rows in the DEFAULT partition takes the
 *       move-then-attach path (the plain CREATE would fail on those rows); a move too
 *       big for one pass leaves the partition detached and the next pass completes and
 *       attaches it; a failing month does not stop the later months.</li>
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
		assertThat(second).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 0, 0));
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

		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 0, 0));
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
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 0, 0));
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
		assertThat(firstPass).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 300, 0));
		assertThat(countEvents("retention-del-%")).isEqualTo(oldBefore - 300);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore + 300);

		// Pass 2: 50 < batch size -> one short batch, then the loop stops.
		PartitionMaintenanceService.MaintenanceSummary secondPass = service.runMaintenance();
		assertThat(secondPass).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 50, 0));
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
	// 6. Review F2: months with rows in the DEFAULT partition (move-then-attach)
	// ---------------------------------------------------------------------

	/**
	 * The F2 regression: the API legally accepts an occurredAt months beyond the managed
	 * window (D13), so a month can enter the lookahead window with rows already in the
	 * DEFAULT partition — there a plain {@code CREATE TABLE ... PARTITION OF} fails with
	 * {@code updated partition constraint for default partition "events_default" would be
	 * violated by some row} and used to block every later month on every pass. The pass
	 * must instead move those rows into the new partition and attach it — exactly once,
	 * no duplicate — while the clean later months still take the unchanged plain path.
	 */
	@Test
	@Order(7)
	void aMonthWithDefaultRowsInRangeIsMovedIntoTheNewPartitionAndLaterMonthsAreStillCreated() {
		testClock.set(scenarioClock(8, 15));
		// The startup window (+8..+11) is in place; strand a row in month +9 and drop
		// all three later window partitions so this pass must rebuild them.
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(9)));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(10)));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(11)));
		assertThat(insertEvent("f2-regression", startOfDayUtc(CURRENT.plusMonths(9), 15))).isEqualTo(1);
		assertThat(routingPartitionOf("f2-regression")).isEqualTo("events_default");

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double movedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		// +9 via move-then-attach, +10 and +11 via the plain path: three created partitions.
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(3, 0, 0, 1));
		for (int offset = 9; offset <= 11; offset++) {
			assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(offset)));
			assertPartitionBounds(partitionName(CURRENT.plusMonths(offset)), CURRENT.plusMonths(offset));
			// The plain-path months were never standalone tables — no leftovers anywhere.
			assertThat(existsAsStandaloneTable(partitionName(CURRENT.plusMonths(offset)))).isFalse();
		}
		// The stranded row now lives in its month's partition, exactly once.
		assertThat(countEvents("f2-regression")).isEqualTo(1);
		assertThat(routingPartitionOf("f2-regression")).isEqualTo(partitionName(CURRENT.plusMonths(9)));
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 3);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED)).isEqualTo(movedBefore + 1);

		jdbcTemplate.update("DELETE FROM events WHERE event_id = 'f2-regression'");
	}

	/**
	 * The move is atomic (review round 2, F2): there is no intermediate DETACHED state and
	 * no batch cap for the move. 350 rows in the month's range are moved and the partition
	 * attached in ONE pass, with every row visible through the {@code events} parent
	 * immediately — a GET, a list query or the idempotency pre-check can never miss them.
	 */
	@Test
	@Order(8)
	void aLargeMoveCompletesAndAttachesInOnePassWithEveryRowVisible() {
		testClock.set(scenarioClock(8, 15));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(10)));
		insertRows(350, startOfDayUtc(CURRENT.plusMonths(10), 5), "f2-move-");
		assertThat(countEvents("f2-move-%")).isEqualTo(350);

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double movedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(1, 0, 0, 350));
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(10)));
		assertPartitionBounds(partitionName(CURRENT.plusMonths(10)), CURRENT.plusMonths(10));
		// Every row is visible through the parent, in its partition, exactly once.
		assertThat(countEvents("f2-move-%")).isEqualTo(350);
		assertThat(routingPartitionOf("f2-move-1")).isEqualTo(partitionName(CURRENT.plusMonths(10)));
		assertThat(routingPartitionOf("f2-move-350")).isEqualTo(partitionName(CURRENT.plusMonths(10)));
		Integer distinctIds = jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT id) FROM events WHERE event_id LIKE 'f2-move-%'", Integer.class);
		assertThat(distinctIds).isEqualTo(350);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED)).isEqualTo(movedBefore + 350);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 1);

		jdbcTemplate.update("DELETE FROM events WHERE event_id LIKE 'f2-move-%'");
	}

	/**
	 * F2 failure isolation: a month that cannot be ensured — here a leftover standalone
	 * table whose shape rejects the moved rows — is caught PER MONTH: the later months of
	 * the window are still created (plain path) and the remaining phases still run. The
	 * stranded row waits in the DEFAULT partition for the next pass, untouched (the
	 * failed move rolled back atomically with its batch).
	 */
	@Test
	@Order(9)
	void aFailingMonthDoesNotStopTheLaterMonthsOrTheOtherPhases() {
		testClock.set(scenarioClock(8, 15));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(9)));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(10)));
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(11)));
		// A standalone +9 table with an unusable shape: the atomic pass's INSERT ... SELECT
		// fails, so the whole month (detach/move/attach) rolls back.
		jdbcTemplate.execute("CREATE TABLE " + partitionName(CURRENT.plusMonths(9)) + " (id BIGINT)");
		assertThat(insertEvent("f2-isolation", startOfDayUtc(CURRENT.plusMonths(9), 15))).isEqualTo(1);

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double droppedBefore = count(AuditMetrics.RETENTION_PARTITIONS_DROPPED);
		double deletedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED);
		double movedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		// +9 failed and its transaction rolled back; +10 and +11 were still created.
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(2, 0, 0, 0));
		assertThat(monthlyPartitionNames()).doesNotContain(partitionName(CURRENT.plusMonths(9)));
		assertThat(existsAsStandaloneTable(partitionName(CURRENT.plusMonths(9)))).isTrue();
		assertThat(monthlyPartitionNames()).contains(partitionName(CURRENT.plusMonths(10)),
				partitionName(CURRENT.plusMonths(11)));
		// The failed move left the DEFAULT partition untouched: the row waits, exactly once.
		assertThat(routingPartitionOf("f2-isolation")).isEqualTo("events_default");
		assertThat(countEvents("f2-isolation")).isEqualTo(1);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 2);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(droppedBefore);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(deletedBefore);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED)).isEqualTo(movedBefore);

		// Cleanup: the rogue table is standalone (not a partition) — dropped by hand.
		jdbcTemplate.execute("DROP TABLE " + partitionName(CURRENT.plusMonths(9)));
		jdbcTemplate.update("DELETE FROM events WHERE event_id = 'f2-isolation'");
	}

	/**
	 * Leftover recovery (review round 2, F2): a standalone monthly table left by the
	 * pre-atomic implementation is invisible through the parent; the recovery sweep
	 * feeds it into the same atomic move-then-attach path EVEN when its month is outside
	 * the lookahead window (far outside every test window), so its rows are never orphaned.
	 */
	@Test
	@Order(10)
	void aLeftoverStandaloneTableOutsideTheWindowIsRecoveredIntoItsPartition() {
		testClock.set(scenarioClock(8, 15));
		// Earlier scenarios drop window months; restore the window first so this pass only
		// counts the leftover recovery (the pre-pass summary is discarded).
		service.runMaintenance();
		YearMonth outside = CURRENT.plusMonths(40); // far outside every test window
		String name = partitionName(outside);
		jdbcTemplate.execute("CREATE TABLE " + name + " (LIKE events INCLUDING DEFAULTS)");
		jdbcTemplate.update("""
				INSERT INTO %s (id, event_id, user_id, event_type, source, occurred_at,
				                received_at, metadata, content_hash, created_at)
				VALUES (9000001, 'f2-leftover', 'user-f2', 'payment.settled', 'core-banking', ?,
				        now(), NULL, 'leftover-hash', now())""".formatted(name),
				startOfDayUtc(outside, 10));
		assertThat(existsAsStandaloneTable(name)).isTrue();

		double createdBefore = count(AuditMetrics.RETENTION_PARTITIONS_CREATED);
		double movedBefore = count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(1, 0, 0, 1));
		assertThat(monthlyPartitionNames()).contains(name);
		assertPartitionBounds(name, outside);
		assertThat(existsAsStandaloneTable(name)).isFalse();
		assertThat(countEvents("f2-leftover")).isEqualTo(1);
		assertThat(routingPartitionOf("f2-leftover")).isEqualTo(name);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(createdBefore + 1);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_MOVED)).isEqualTo(movedBefore + 1);

		jdbcTemplate.update("DELETE FROM events WHERE event_id = 'f2-leftover'");
		jdbcTemplate.execute("DROP TABLE " + name);
	}

	/**
	 * Legacy duplicate recovery (review round 3, F1): the pre-atomic implementation could
	 * leave duplicate (occurred_at, event_id) rows in a standalone table (and a copy in the
	 * default partition). The recovery must dedupe instead of failing on the unique index:
	 * one row per pair survives (the default's row wins), distinct rows are preserved, the
	 * sweep skips an invalid monthly name (events_2024_13) without aborting, and the pass
	 * reports the three distinct rows it moved.
	 */
	@Test
	@Order(11)
	void aLegacyLeftoverWithDuplicateRowsIsRecoveredWithoutFailing() {
		testClock.set(scenarioClock(8, 15));
		service.runMaintenance(); // restore any window months dropped by earlier scenarios
		YearMonth outside = CURRENT.plusMonths(41);
		String name = partitionName(outside);
		OffsetDateTime when = startOfDayUtc(outside, 10);
		// An invalid monthly name in the sweep: matched by the shape, unparseable as a month.
		jdbcTemplate.execute("CREATE TABLE events_2024_13 (LIKE events INCLUDING DEFAULTS)");
		jdbcTemplate.execute("CREATE TABLE " + name + " (LIKE events INCLUDING DEFAULTS)");
		// Duplicate pair INSIDE the leftover, plus a distinct row.
		insertLeftoverRow(name, 9_100_001L, "f2-dup-inside", when);
		insertLeftoverRow(name, 9_100_002L, "f2-dup-inside", when);
		insertLeftoverRow(name, 9_100_003L, "f2-dup-keep", when);
		// Duplicate pair ACROSS the default and the leftover: the default's row must win.
		assertThat(insertEvent("f2-dup-default", when)).isEqualTo(1);
		insertLeftoverRow(name, 9_100_004L, "f2-dup-default", when);

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		// Three distinct pairs move; the two duplicate copies are skipped, no failure.
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(1, 0, 0, 3));
		assertThat(monthlyPartitionNames()).contains(name);
		assertThat(existsAsStandaloneTable(name)).isFalse();
		assertThat(countEvents("f2-dup-inside")).isEqualTo(1);
		assertThat(countEvents("f2-dup-keep")).isEqualTo(1);
		assertThat(countEvents("f2-dup-default")).isEqualTo(1);
		assertThat(routingPartitionOf("f2-dup-inside")).isEqualTo(name);
		assertThat(routingPartitionOf("f2-dup-keep")).isEqualTo(name);

		jdbcTemplate.update("DELETE FROM events WHERE event_id LIKE 'f2-dup-%'");
		jdbcTemplate.execute("DROP TABLE " + name);
		jdbcTemplate.execute("DROP TABLE events_2024_13");
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

	/** Inserts one row into a legacy leftover standalone table (no unique index there). */
	private void insertLeftoverRow(String table, long id, String eventId, OffsetDateTime when) {
		jdbcTemplate.update("""
				INSERT INTO %s (id, event_id, user_id, event_type, source, occurred_at, received_at,
				                metadata, content_hash, created_at)
				VALUES (?, ?, 'user-f2', 'payment.settled', 'core-banking', ?, now(), NULL, 'h', now())"""
				.formatted(table), id, eventId, when);
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

	/**
	 * True when a non-partition table of that name exists: a leftover standalone table
	 * from the F2 move path (an attached partition is in pg_inherits, a standalone table
	 * is not).
	 */
	private boolean existsAsStandaloneTable(String name) {
		Integer standalone = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM pg_class c
				WHERE c.oid = to_regclass('public.' || ?) AND c.relkind = 'r'
				  AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)""",
				Integer.class, name);
		return standalone != null && standalone > 0;
	}

}
