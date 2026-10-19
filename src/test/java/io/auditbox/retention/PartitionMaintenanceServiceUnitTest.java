package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import io.auditbox.metrics.AuditMetrics;

/**
 * Pure unit tests for {@link PartitionMaintenanceService} with a Mockito-stubbed
 * {@link JdbcTemplate} (no Spring context, no database) — the M11 review round 2 gaps:
 *
 * <ul>
 *   <li>review F6/F8d — partial phase summaries: a phase that fails after doing part of
 *       the work keeps the counts already achieved in the summary, the drop phase is
 *       isolated PER PARTITION (one failing DROP does not abort the rest of the phase)
 *       and the phases after the failing one still run,</li>
 *   <li>review F8a — the name guard: an unexpected child table (here {@code events_extra}
 *       with a perfectly valid range bound) is never dropped, and neither is the DEFAULT
 *       partition,</li>
 *   <li>review F8b — the overlap guard: while a pass is inside a phase, a second
 *       concurrent {@code runMaintenance()} returns a zero summary and executes no
 *       phases (proven with latches, no sleeps).</li>
 * </ul>
 *
 * <p>The stubs mirror the service's real interaction shapes: the catalog query feeds
 * {@link RowCallbackHandler} rows of (name, bound), the {@code EXISTS}/
 * {@code to_regclass} probes answer {@link Boolean}, DDL goes through
 * {@code execute(String)} and the batched DELETE through {@code update(String, Object...)}.
 */
class PartitionMaintenanceServiceUnitTest {

	/** The fixed scenario clock: 2026-06-15 10:00 UTC, so the derived months are stable. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);

	private JdbcTemplate jdbcTemplate;

	private AuditMetrics metrics;

	@BeforeEach
	void setUp() {
		jdbcTemplate = mock(JdbcTemplate.class);
		metrics = mock(AuditMetrics.class);
	}

	// ---------------------------------------------------------------------
	// Review F6/F8d — partial phase summaries
	// ---------------------------------------------------------------------

	/**
	 * The third CREATE fails (caught per month, review F2): the summary still reports the
	 * two partitions that WERE created, and the later phases (drop, delete) still ran.
	 */
	@Test
	void aFailingThirdCreateKeepsTheFirstTwoCountsAndTheLaterPhasesStillRun() {
		PartitionMaintenanceService service = new PartitionMaintenanceService(jdbcTemplate,
				properties(240, 2, 1000, 10), metrics, CLOCK);
		// No existing partitions (the unstubbed catalog query never calls the handler)
		// and no DEFAULT-partition rows in any range (the unstubbed probe answers null):
		// every lookahead month takes the plain create path.
		AtomicInteger ddl = new AtomicInteger();
		doAnswer(invocation -> {
			if (ddl.incrementAndGet() == 3) {
				throw new IllegalStateException("create fail #3");
			}
			return null;
		}).when(jdbcTemplate).execute(anyString());

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary.partitionsCreated()).isEqualTo(2);
		assertThat(summary.partitionsDropped()).isZero();
		assertThat(summary.defaultRowsDeleted()).isZero();
		assertThat(ddl.get()).isEqualTo(3); // two creates + the failing third
		verify(metrics, times(2)).retentionPartitionCreated();
		// The later phases still ran: the batched DEFAULT DELETE was attempted once.
		verify(jdbcTemplate, times(1)).update(anyString(), any(OffsetDateTime.class), anyInt());
	}

	/**
	 * THE F6 case: the third DROP fails — before the fix the whole drop phase aborted and
	 * the summary reported 0. Now the failure is isolated per partition: partitions one,
	 * two and FOUR (after the failing one) still drop, the summary counts them, and the
	 * later delete phase still ran.
	 */
	@Test
	void aFailingThirdDropIsIsolatedPerPartitionAndTheSummaryCountsTheRest() {
		PartitionMaintenanceService service = new PartitionMaintenanceService(jdbcTemplate,
				properties(1, 0, 1000, 10), metrics, CLOCK);
		// Four fully expired monthly partitions (upper bounds far before the 2026-05-15
		// cutoff) plus the current month's keeper.
		stubCatalogPartitions(jdbcTemplate, new String[][] {
				{ "events_2024_01", "FOR VALUES FROM ('2024-01-01 00:00:00+00') TO ('2024-02-01 00:00:00+00')" },
				{ "events_2024_02", "FOR VALUES FROM ('2024-02-01 00:00:00+00') TO ('2024-03-01 00:00:00+00')" },
				{ "events_2024_03", "FOR VALUES FROM ('2024-03-01 00:00:00+00') TO ('2024-04-01 00:00:00+00')" },
				{ "events_2024_04", "FOR VALUES FROM ('2024-04-01 00:00:00+00') TO ('2024-05-01 00:00:00+00')" },
				{ "events_2026_06", "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')" } });
		AtomicInteger drops = new AtomicInteger();
		doAnswer(invocation -> {
			if (drops.incrementAndGet() == 3) {
				throw new IllegalStateException("drop fail #3");
			}
			return null;
		}).when(jdbcTemplate).execute(anyString());

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		// 01 and 02 dropped, 03 failed, 04 dropped anyway, 2026_06 kept by the rule.
		assertThat(summary.partitionsDropped()).isEqualTo(3);
		assertThat(summary.partitionsCreated()).isZero();
		verify(metrics, times(3)).retentionPartitionDropped();
		// The later phase still ran.
		verify(jdbcTemplate, times(1)).update(anyString(), any(OffsetDateTime.class), anyInt());
	}

	/**
	 * The third DELETE batch fails: the summary keeps the two full batches already
	 * achieved (200 rows) instead of reporting 0.
	 */
	@Test
	void aFailingThirdDeleteBatchKeepsTheCountsAlreadyAchieved() {
		PartitionMaintenanceService service = new PartitionMaintenanceService(jdbcTemplate,
				properties(1, 0, 100, 10), metrics, CLOCK);
		// The current month exists and overlaps the window: ensure and drop are quiet.
		stubCatalogPartitions(jdbcTemplate, new String[][] {
				{ "events_2026_06", "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')" } });
		AtomicInteger batches = new AtomicInteger();
		when(jdbcTemplate.update(anyString(), any(OffsetDateTime.class), anyInt())).thenAnswer(invocation -> {
			if (batches.incrementAndGet() == 3) {
				throw new IllegalStateException("delete fail #3");
			}
			return 100; // a full batch, so the loop continues to the next one
		});

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary.defaultRowsDeleted()).isEqualTo(200);
		assertThat(batches.get()).isEqualTo(3);
		verify(metrics, times(2)).retentionDefaultRowsDeleted(100);
	}

	// ---------------------------------------------------------------------
	// Review F8a — the name guard
	// ---------------------------------------------------------------------

	/**
	 * An unexpected child table with a VALID (fully expired) range bound — here
	 * {@code events_extra} — is never dropped, and neither is the DEFAULT partition,
	 * even though their bounds would make them droppable by the rule.
	 */
	@Test
	void anUnexpectedChildTableIsNeverDroppedDespiteAValidExpiredBound() {
		PartitionMaintenanceService service = new PartitionMaintenanceService(jdbcTemplate,
				properties(1, 0, 1000, 10), metrics, CLOCK);
		stubCatalogPartitions(jdbcTemplate, new String[][] {
				{ "events_2024_01", "FOR VALUES FROM ('2024-01-01 00:00:00+00') TO ('2024-02-01 00:00:00+00')" },
				{ "events_2026_06", "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')" },
				{ "events_default", "DEFAULT" },
				{ "events_extra", "FOR VALUES FROM ('2020-01-01 00:00:00+00') TO ('2020-02-01 00:00:00+00')" } });

		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();

		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 1, 0));
		// Exactly one DROP — the one monthly-named expired partition.
		verify(jdbcTemplate, times(1)).execute(anyString());
		verify(jdbcTemplate).execute("DROP TABLE events_2024_01");
		verify(jdbcTemplate, never()).execute(contains("events_extra"));
		verify(jdbcTemplate, never()).execute(contains("events_default"));
		verify(metrics, times(1)).retentionPartitionDropped();
	}

	// ---------------------------------------------------------------------
	// Review F8b — the overlap guard
	// ---------------------------------------------------------------------

	/**
	 * While a first pass is blocked INSIDE a phase (here: reading the catalog), a second
	 * concurrent {@code runMaintenance()} returns a zero summary immediately and executes
	 * no phase at all. Latches only — no sleeps.
	 */
	@Test
	void anOverlappingSecondRunReturnsAZeroSummaryAndExecutesNoPhases() throws Exception {
		PartitionMaintenanceService service = new PartitionMaintenanceService(jdbcTemplate,
				properties(240, 0, 1000, 10), metrics, CLOCK);

		CountDownLatch firstPassInsidePhase = new CountDownLatch(1);
		CountDownLatch releaseFirstPass = new CountDownLatch(1);
		// Any phase execution of the SECOND pass would have to touch one of these
		// statement methods — flag it instead of trusting the zero summary alone.
		AtomicBoolean phaseInteraction = new AtomicBoolean(false);
		doAnswer(invocation -> {
			firstPassInsidePhase.countDown();
			// Safety timeout instead of an infinite await: a wedged test fails instead
			// of hanging the suite.
			releaseFirstPass.await(5, TimeUnit.SECONDS);
			return null;
		}).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
		doAnswer(invocation -> {
			phaseInteraction.set(true);
			return null;
		}).when(jdbcTemplate).execute(anyString());
		when(jdbcTemplate.update(anyString(), any(OffsetDateTime.class), anyInt()))
				.thenAnswer(invocation -> {
					phaseInteraction.set(true);
					return 0;
				});
		when(jdbcTemplate.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class),
				any(OffsetDateTime.class), any(OffsetDateTime.class))).thenAnswer(invocation -> {
					phaseInteraction.set(true);
					return Boolean.FALSE;
				});

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<PartitionMaintenanceService.MaintenanceSummary> firstPass = executor
					.submit(service::runMaintenance);
			// Wait until the first pass is provably INSIDE runPhases (the overlap guard
			// is already set) — no sleeps, the latch fires from the stub itself.
			assertThat(firstPassInsidePhase.await(5, TimeUnit.SECONDS)).isTrue();

			// The second, overlapping trigger: skipped, zero summary, nothing executed.
			PartitionMaintenanceService.MaintenanceSummary second = service.runMaintenance();
			assertThat(second).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 0));
			assertThat(phaseInteraction).as("no phase may execute for the overlapping trigger").isFalse();

			releaseFirstPass.countDown();
			assertThat(firstPass.get(5, TimeUnit.SECONDS)).isNotNull();
		}
		finally {
			executor.shutdownNow();
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static RetentionProperties properties(int months, int lookaheadMonths, int batchSize,
			int maxBatches) {
		return new RetentionProperties(true, months, lookaheadMonths, batchSize, maxBatches, "0 30 3 * * *");
	}

	/** Makes the catalog query return the given (name, bound) rows via the handler. */
	private static void stubCatalogPartitions(JdbcTemplate jdbcTemplate, String[][] nameAndBound) {
		doAnswer(invocation -> {
			RowCallbackHandler handler = invocation.getArgument(1);
			for (String[] row : nameAndBound) {
				ResultSet resultSet = mock(ResultSet.class);
				when(resultSet.getString("name")).thenReturn(row[0]);
				when(resultSet.getString("bound")).thenReturn(row[1]);
				handler.processRow(resultSet);
			}
			return null;
		}).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
	}

}
