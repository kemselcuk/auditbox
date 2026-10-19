package io.auditbox.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.auditbox.metrics.AuditMetrics;

/**
 * Partition maintenance and retention for the monthly RANGE-partitioned {@code events}
 * table (M11.2, decision D22; the partitioning itself is D21). One
 * {@link #runMaintenance()} pass, driven by {@link PartitionMaintenanceScheduler} once at
 * application startup (after Flyway) and then on the daily cron:
 *
 * <ol>
 *   <li><b>Ensure the forward window:</b> for every month from the current month (UTC)
 *       through {@code lookahead-months} ahead (inclusive), create the missing
 *       {@code events_YYYY_MM} partition with half-open bounds
 *       {@code [month start UTC, next month start UTC)} — the exact layout the V2
 *       migration built, so the DEFAULT partition keeps catching only out-of-window
 *       {@code occurred_at} values. A month whose range already holds rows in the
 *       DEFAULT partition (the API legally accepts any {@code occurredAt} in
 *       0001-01-01..9999-12-31, D13, so a client can write months beyond the window;
 *       M11 review F2) takes the atomic move-then-attach path
 *       ({@link #moveDefaultRowsAndAttachAtomic(String, YearMonth, PhaseTotals)}) instead
 *       of the plain {@code CREATE TABLE ... PARTITION OF} — those rows would violate the
 *       DEFAULT partition's updated constraint and fail the month. One month's failure
 *       is caught per month and logged; the later months of the window are still
 *       processed, and a window-independent sweep recovers leftover standalone tables,</li>
 *   <li><b>Apply retention by dropping:</b> a monthly partition is droppable when its
 *       upper bound is {@code <=} now(UTC) − {@code months} (the whole range is older
 *       than the retention window; a partition overlapping the window is kept, one whose
 *       upper bound is exactly the cutoff is dropped). {@code DROP TABLE} removes the
 *       partition's rows together with the table — <b>this delete is the retention
 *       policy by design</b>: AuditBox records what it receives and is not claimed to be
 *       tamper-proof or immutable (see README "What this is / is not"),</li>
 *   <li><b>Bounded DEFAULT-partition cleanup:</b> rows with
 *       {@code occurred_at <} the same cutoff are deleted from {@code events_default} in
 *       batches of {@code default-delete-batch-size} rows, at most
 *       {@code max-batches-per-run} batches per pass (looping until a batch deletes
 *       fewer rows than its size or the cap is reached), so one pass never runs an
 *       unbounded DELETE. Monthly partitions never see this DELETE — they are dropped
 *       whole.</li>
 * </ol>
 *
 * <p>All time math is UTC (D10, matching the app's {@code occurredAt} normalization) and
 * derived from the injected {@link Clock}: the current month and the cutoff are computed
 * here, and the DDL string is built ONLY from such clock-derived months — no user input
 * or other string ever reaches a partition name (always {@code events_} + {@code YYYY_MM})
 * or a bound literal. The DDL bound literals are absolute UTC offsets, so they do not
 * depend on the database session's timezone. Drop decisions read the partition bounds
 * from the PostgreSQL catalog instead of trusting the name — and the catalog renders
 * those bound literals in the DATABASE SESSION's timezone, so the parser accepts every
 * ISO-8601 offset form PostgreSQL renders ({@code +00}, {@code -05}, minute offsets like
 * {@code +05:30}/{@code +05:45}, {@code Z}; review F3 — before the fix, a session zone
 * with a minute offset such as Asia/Kolkata made every bound unparseable, every
 * partition was skipped (kept) and retention silently stopped). A partition whose bound
 * cannot be parsed is skipped (kept) with a warning — never dropped on a guess.
 *
 * <p>An in-process {@link AtomicBoolean} guards against overlapping passes (the startup
 * trigger and the cron share it): an overlapping trigger is skipped, not queued. A phase
 * failure is caught, logged with its phase name and does not abort the remaining phases,
 * so one broken step cannot block retention progress; the summary counts what the pass
 * actually did INCLUDING the work done before a mid-phase failure (review F6): the
 * phases accumulate their counters as the work happens, and a failing {@code DROP} is
 * isolated per partition — one failing table does not abort the rest of the drop phase.
 * With {@code enabled=false} the pass returns immediately: no DDL, no
 * deletes, no counter increments. Counters (M11.2): {@code auditbox.retention.
 * partitions.created}, {@code auditbox.retention.partitions.dropped},
 * {@code auditbox.retention.default.rows.deleted} and — for the F2 move-then-attach —
 * {@code auditbox.retention.default.rows.moved}. Nothing key-related can appear in
 * these logs or counters — the job touches schema objects and row counts only.
 */
@Service
public class PartitionMaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceService.class);

	/**
	 * Monthly children created by V2 and by this job get this exact name shape; only
	 * children matching it are ever considered for a DROP (the DEFAULT partition and any
	 * unexpected child table are never touched by this phase).
	 */
	private static final Pattern MONTHLY_PARTITION_NAME = Pattern.compile("events_\\d{4}_\\d{2}");

	/** The TO literal of the range-bound expression pg_get_expr renders in the catalog. */
	private static final Pattern BOUND_UPPER = Pattern.compile("TO \\('([^']+)'\\)");

	/** Partition names: events + YYYY_MM, same formatting as the V2 DO block (to_char). */
	private static final DateTimeFormatter MONTH_NAME_FORMAT = DateTimeFormatter.ofPattern("uuuu_MM", Locale.ROOT);

	/** Bound literals in the CREATE DDL: 'YYYY-MM-DD HH:MM:SS+00' (UTC-pinned, like V2). */
	private static final DateTimeFormatter BOUND_LITERAL_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

	/**
	 * The trailing ISO-8601 offset of a catalog bound literal, as PostgreSQL renders it
	 * for the DATABASE session's timezone: {@code +00} (UTC), whole-hour offsets like
	 * {@code -05}, minute offsets like {@code +05:30}/{@code +05:45} (review F3), with
	 * or without the colon. Group 1 is the hour part, group 2 the (possibly absent)
	 * minute part. Second-precision offsets are not rendered by any common zone and are
	 * rejected (the partition is then kept, the safe direction).
	 */
	private static final Pattern BOUND_OFFSET_SUFFIX = Pattern.compile("([+-]\\d{2})(?::?(\\d{2}))?$");

	private final JdbcTemplate jdbcTemplate;

	private final RetentionProperties properties;

	private final AuditMetrics metrics;

	/** The single time source for every decision; tests inject a fixed clock. */
	private final Clock clock;

	/**
	 * Transaction boundary for the atomic DEFAULT-partition move (review round 2, F2):
	 * detach the default partition, create the monthly partition, move its rows and
	 * re-attach the default all commit together or not at all.
	 */
	private final TransactionTemplate moveTransaction;

	/** Overlap guard, shared by the startup pass and the cron pass. */
	private final AtomicBoolean running = new AtomicBoolean();

	PartitionMaintenanceService(JdbcTemplate jdbcTemplate, RetentionProperties properties,
			AuditMetrics metrics, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
		this.moveTransaction = new TransactionTemplate(transactionManager);
	}

	/** What one maintenance pass actually did; also the shape of the logged summary. */
	public record MaintenanceSummary(int partitionsCreated, int partitionsDropped, long defaultRowsDeleted,
			long defaultRowsMoved) {
	}

	/**
	 * Runs one maintenance pass (ensure window, apply retention, bounded default cleanup)
	 * unless the feature is disabled or another pass is already running in this process.
	 *
	 * @return the summary of what this pass did (all zeros for a skipped pass)
	 */
	public MaintenanceSummary runMaintenance() {
		if (!properties.enabled()) {
			// Disabled (D22): a strict no-op — the counters stay untouched too, so the
			// metrics only ever report work the enabled job did.
			log.debug("Partition maintenance is disabled (auditbox.retention.enabled=false); skipping");
			return new MaintenanceSummary(0, 0, 0, 0);
		}
		if (!running.compareAndSet(false, true)) {
			log.warn("Partition maintenance is already running in this process; skipping the overlapping trigger");
			return new MaintenanceSummary(0, 0, 0, 0);
		}
		try {
			return runPhases();
		}
		finally {
			running.set(false);
		}
	}

	private MaintenanceSummary runPhases() {
		Instant now = Instant.now(clock);
		// The current month in UTC, from the clock (tests pin this via the injected clock).
		YearMonth currentMonth = YearMonth.from(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
		// Retention cutoff: now(UTC) - months. OffsetDateTime (not Instant) because
		// Instant arithmetic does not support calendar months; the subtraction is month-
		// length aware and stays on the UTC timeline (D10).
		Instant cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
				.minus(properties.months(), ChronoUnit.MONTHS)
				.toInstant();
		log.info("Partition maintenance starting: current month {}, retention {} month(s) (cutoff {}), "
				+ "lookahead {} month(s), window bounds [month start UTC, next month start UTC)",
				currentMonth, properties.months(), cutoff, properties.lookaheadMonths());

		// Review F6: the phases accumulate into shared totals as the work happens, so a
		// failure after partial work keeps the counts already achieved instead of
		// reporting a zero summary for a half-done pass.
		PhaseTotals totals = new PhaseTotals();
		try {
			ensureLookaheadPartitions(currentMonth, monthlyPartitionsWithBounds().keySet(), totals);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: creating the lookahead window failed", e);
		}
		try {
			recoverLeftoverStandaloneTables(totals);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: recovering leftover standalone partition tables failed", e);
		}
		try {
			dropExpiredPartitions(cutoff, totals);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: dropping expired partitions failed", e);
		}
		try {
			deleteExpiredDefaultRows(cutoff, totals);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: deleting expired DEFAULT-partition rows failed", e);
		}

		// Concise summary, no key material involved anywhere (schema objects and counts only).
		log.info("Partition maintenance done: {} partition(s) created, {} partition(s) dropped, "
				+ "{} DEFAULT-partition row(s) deleted, {} DEFAULT-partition row(s) moved",
				totals.partitionsCreated, totals.partitionsDropped, totals.defaultRowsDeleted,
				totals.defaultRowsMoved);
		return new MaintenanceSummary(totals.partitionsCreated, totals.partitionsDropped,
				totals.defaultRowsDeleted, totals.defaultRowsMoved);
	}

	/** Mutable accumulator for the phase counters (review F6); not thread-safe on
	 * purpose — one pass runs alone (the overlap guard). */
	private static final class PhaseTotals {

		private int partitionsCreated;
		private int partitionsDropped;
		private long defaultRowsDeleted;
		private long defaultRowsMoved;
	}

	/**
	 * Phase 1: for every month current..current+lookahead (inclusive), create the missing
	 * monthly partition with the V2-style bounds. Existing partitions are left untouched,
	 * so a pass that finds the whole window in place creates nothing (idempotent).
	 * Counts every created partition into the pass totals as it happens (review F6), so
	 * a later failure keeps the earlier creations in the summary.
	 */
	private void ensureLookaheadPartitions(YearMonth currentMonth, Set<String> existingPartitions,
			PhaseTotals totals) {
		for (int offset = 0; offset <= properties.lookaheadMonths(); offset++) {
			YearMonth month = currentMonth.plusMonths(offset);
			String partitionName = partitionName(month);
			if (existingPartitions.contains(partitionName)) {
				continue;
			}
			try {
				ensureMissingPartition(partitionName, month, totals);
			}
			catch (RuntimeException e) {
				// Per-month failure isolation (review F2): one broken month — a DDL
				// failure, a locked catalog, a colliding leftover table — must not stop
				// the later months of the window. The phase caller catches whatever
				// escapes here anyway, so the other phases always run too.
				log.error("Partition maintenance: ensuring the partition {} failed; "
						+ "the later months of the window are still processed", partitionName, e);
			}
		}
	}

	/**
	 * Ensures one missing month of the window. Without DEFAULT-partition rows in the
	 * month's range and without a leftover standalone table this is the plain DDL path;
	 * otherwise the atomic move-then-attach path runs (review round 2, F2). Both paths
	 * increment the pass totals; a failed atomic pass rolls back and is retried next pass.
	 */
	private void ensureMissingPartition(String partitionName, YearMonth month, PhaseTotals totals) {
		boolean defaultRowsInRange = defaultPartitionHasRowsInRange(month);
		if (!defaultRowsInRange && !leftoverStandaloneTableExists(partitionName)) {
			createPartition(partitionName, month);
			metrics.retentionPartitionCreated();
			totals.partitionsCreated++;
			log.info("Created monthly partition {} for window [{}, {})", partitionName,
					startOfMonth(month), startOfMonth(month.plusMonths(1)));
			return;
		}
		moveDefaultRowsAndAttachAtomic(partitionName, month, totals);
	}

	/**
	 * Creating a missing partition can race with another replica running the same job
	 * (D22 single-instance note); IF NOT EXISTS turns that rare race into a no-op instead
	 * of a failed pass. The created count can therefore over-count by such a losing
	 * racing pass — accepted and documented.
	 */
	private void createPartition(String partitionName, YearMonth month) {
		String lower = BOUND_LITERAL_FORMAT.format(startOfMonth(month)) + "+00";
		String upper = BOUND_LITERAL_FORMAT.format(startOfMonth(month.plusMonths(1))) + "+00";
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + partitionName + " PARTITION OF events"
				+ " FOR VALUES FROM ('" + lower + "') TO ('" + upper + "')");
	}

	/**
	 * Atomic move-then-attach for a month whose range holds DEFAULT-partition rows (or a
	 * leftover standalone table from the pre-atomic implementation). The whole sequence
	 * runs in ONE transaction (review round 2, F2):
	 *
	 * <ol>
	 *   <li>detach the DEFAULT partition ({@code ALTER TABLE events DETACH PARTITION
	 *       events_default}) — PostgreSQL refuses to create a partition while the attached
	 *       default holds rows in its range, and the atomic transaction means readers see
	 *       either the old layout or the new one, never a half-applied state,</li>
	 *   <li>recover a leftover standalone table from the previous implementation: its rows
	 *       go back into the detached default (that is where they came from), then the
	 *       leftover table is dropped,</li>
	 *   <li>create the monthly partition (no default attached, so the range is free),</li>
	 *   <li>move EVERY row of the month's range from the detached default into the new
	 *       partition (one statement; the move must complete for the re-attach to
	 *       validate),</li>
	 *   <li>re-attach the DEFAULT partition ({@code ATTACH PARTITION events_default
	 *       DEFAULT}); PostgreSQL validates that no remaining default row falls into an
	 *       existing partition,</li>
	 *   <li>commit — rows become visible through the {@code events} parent at the same
	 *       moment the old layout stops existing; there is no window in which a GET, a
	 *       list query or the idempotency pre-check misses a moved row.</li>
	 * </ol>
	 *
	 * <p>The transaction takes an ACCESS EXCLUSIVE lock on {@code events} for its duration
	 * (the detach/attach requirement) and therefore briefly blocks API traffic while it
	 * runs; the DEFAULT partition is expected to be small (only dates outside the managed
	 * window), so the move is short. A failure rolls everything back: the default stays
	 * attached, no partition is created and no row is lost — the next pass retries. Moved
	 * rows are counted in {@code auditbox.retention.default.rows.moved} and in the pass
	 * summary after commit.
	 */
	private void moveDefaultRowsAndAttachAtomic(String partitionName, YearMonth month, PhaseTotals totals) {
		Long movedRows;
		try {
			movedRows = moveTransaction.execute(status -> {
				jdbcTemplate.execute("ALTER TABLE events DETACH PARTITION events_default");
				if (leftoverStandaloneTableExists(partitionName)) {
					recoverLeftoverRows(partitionName);
				}
				createPartition(partitionName, month);
				long moved = moveDefaultRows(partitionName, month);
				jdbcTemplate.execute("ALTER TABLE events ATTACH PARTITION events_default DEFAULT");
				return moved;
			});
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: the atomic move into {} failed; the transaction rolled back "
					+ "(the DEFAULT partition is still attached) and the next pass will retry", partitionName, e);
			return;
		}
		long moved = movedRows == null ? 0 : movedRows;
		totals.partitionsCreated++;
		totals.defaultRowsMoved += moved;
		metrics.retentionPartitionCreated();
		if (moved > 0) {
			metrics.retentionDefaultRowsMoved(moved);
		}
		log.info("Created monthly partition {} and moved {} DEFAULT-partition row(s) into it "
				+ "(atomic detach/move/attach)", partitionName, moved);
	}

	/** Does events_default hold at least one row inside the month's half-open UTC range? */
	private boolean defaultPartitionHasRowsInRange(YearMonth month) {
		Boolean exists = jdbcTemplate.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM events_default
				               WHERE occurred_at >= ? AND occurred_at < ?)""",
				Boolean.class, startOfMonthUtc(month), startOfMonthUtc(month.plusMonths(1)));
		return Boolean.TRUE.equals(exists);
	}

	/** Moves every row of the month's range out of the (detached) DEFAULT partition. */
	private long moveDefaultRows(String partitionName, YearMonth month) {
		return jdbcTemplate.update("""
				WITH moved AS (
				    DELETE FROM events_default WHERE occurred_at >= ? AND occurred_at < ?
				    RETURNING *)
				INSERT INTO %s
				    (id, event_id, user_id, event_type, source, occurred_at, received_at, metadata,
				     content_hash, created_at)
				SELECT id, event_id, user_id, event_type, source, occurred_at, received_at, metadata,
				       content_hash, created_at
				FROM moved""".formatted(partitionName),
				startOfMonthUtc(month), startOfMonthUtc(month.plusMonths(1)));
	}

	/**
	 * Puts a legacy leftover table's rows back into the (detached) DEFAULT partition,
	 * tolerating the duplicate state the pre-atomic implementation could leave behind
	 * (review round 3, F1): a retry while the standalone table was invisible inserted a
	 * second row with the same {@code (occurred_at, event_id)} into the default, and the
	 * old code could copy both into the standalone table, which carried no unique index.
	 * The recovery keeps ONE row per {@code (occurred_at, event_id)} — the row already in
	 * the default wins, otherwise the first leftover row — and logs how many duplicate
	 * rows it skipped. A legacy eventId with DIFFERENT occurredAt values (same id, different
	 * content) is not merged: both rows are preserved (data is never destroyed) and the
	 * count of such duplicate eventIds in the default is logged; the API's advisory lock
	 * prevents new ones.
	 */
	private void recoverLeftoverRows(String partitionName) {
		Integer leftoverRows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + partitionName, Integer.class);
		int inserted = jdbcTemplate.update("""
				INSERT INTO events_default
				    (id, event_id, user_id, event_type, source, occurred_at, received_at,
				     metadata, content_hash, created_at)
				SELECT id, event_id, user_id, event_type, source, occurred_at, received_at,
				       metadata, content_hash, created_at
				FROM %s legacy
				WHERE NOT EXISTS (SELECT 1 FROM events_default existing
				                  WHERE existing.occurred_at = legacy.occurred_at
				                    AND existing.event_id = legacy.event_id)
				ON CONFLICT (occurred_at, event_id) DO NOTHING""".formatted(partitionName));
		int skipped = (leftoverRows == null ? 0 : leftoverRows) - inserted;
		if (skipped > 0) {
			log.warn("Recovering leftover {}: skipped {} duplicate (occurred_at, eventId) row(s) "
					+ "left by the pre-atomic implementation", partitionName, skipped);
		}
		Integer duplicateEventIds = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM (
				    SELECT event_id FROM events_default GROUP BY event_id HAVING count(*) > 1
				) duplicates""", Integer.class);
		if (duplicateEventIds != null && duplicateEventIds > 0) {
			log.warn("events_default holds {} legacy eventId value(s) with multiple rows (different "
					+ "occurredAt): preserved as-is; the API's per-eventId lock prevents new ones",
					duplicateEventIds);
		}
		jdbcTemplate.execute("DROP TABLE " + partitionName);
	}

	/**
	 * True when a relation with the month's name exists but is NOT a child of the
	 * {@code events} parent: a leftover standalone table from the pre-atomic
	 * implementation (or a hand-created table). Such a table is never visible through the
	 * parent, so it is recovered into the partition by the atomic pass above.
	 */
	private boolean leftoverStandaloneTableExists(String partitionName) {
		Boolean leftover = jdbcTemplate.queryForObject("""
				SELECT to_regclass(?) IS NOT NULL
				   AND EXISTS (SELECT 1 FROM pg_class c
				               JOIN pg_namespace n ON n.oid = c.relnamespace
				               WHERE c.oid = to_regclass(?) AND c.relkind = 'r'
				                 AND n.nspname = 'public')
				   AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = to_regclass(?))""",
				Boolean.class, "public." + partitionName, "public." + partitionName,
				"public." + partitionName);
		return Boolean.TRUE.equals(leftover);
	}

	/**
	 * Recovery sweep for leftover standalone tables from the pre-atomic implementation:
	 * a standalone table is invisible through the {@code events} parent, so its rows would
	 * never be read or retained. Every monthly-named standalone table is fed into the same
	 * atomic move-then-attach path (its rows are put back into the DEFAULT partition and
	 * then moved into the new partition). The sweep is independent of the lookahead
	 * window, so a leftover for a month that has already left the window is still
	 * recovered.
	 */
	private void recoverLeftoverStandaloneTables(PhaseTotals totals) {
		List<String> leftovers = jdbcTemplate.query("""
				SELECT c.relname AS name
				FROM pg_class c
				JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'public' AND c.relkind = 'r'
				  AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)""",
				(resultSet, rowNum) -> resultSet.getString("name"));
		for (String name : leftovers) {
			if (!MONTHLY_PARTITION_NAME.matcher(name).matches()) {
				continue; // events_default or an unrelated table: never touched here
			}
			try {
				YearMonth month = YearMonth.parse(name.substring("events_".length()).replace('_', '-'));
				ensureMissingPartition(name, month, totals);
			}
			catch (RuntimeException e) {
				// Includes an unparseable name such as events_2024_13: skip it, keep going.
				log.error("Partition maintenance: recovering the leftover standalone table {} failed; "
						+ "the later leftovers and phases are still processed", name, e);
			}
		}
	}

	/**
	 * Phase 2: drop every monthly partition whose upper bound (read from the catalog, not
	 * derived from the name) is at or before the cutoff. See the class javadoc for the
	 * exact rule and the rows-removed-by-drop semantics. Counts every drop into the pass
	 * totals as it happens (review F6), and the failure isolation is PER PARTITION: one
	 * failing DROP is caught and logged while the rest of the phase continues — the
	 * failed partition simply waits for the next pass.
	 */
	private void dropExpiredPartitions(Instant cutoff, PhaseTotals totals) {
		Map<String, String> partitions = new TreeMap<>(monthlyPartitionsWithBounds());
		for (Map.Entry<String, String> partition : partitions.entrySet()) {
			String name = partition.getKey();
			if (!MONTHLY_PARTITION_NAME.matcher(name).matches()) {
				// Never touch the DEFAULT partition or any unexpected child here.
				continue;
			}
			Instant upper = upperBound(name, partition.getValue());
			if (upper == null || upper.isAfter(cutoff)) {
				// Overlaps the retention window (or its bound is unreadable): keep it.
				continue;
			}
			try {
				// No IF EXISTS: the pass decided this table is droppable; a concurrent
				// drop by another replica would fail this partition (caught below, the
				// phase continues) instead of silently miscounting (D22 multi-instance
				// note).
				jdbcTemplate.execute("DROP TABLE " + name);
			}
			catch (RuntimeException e) {
				log.error("Partition maintenance: dropping the partition {} failed; "
						+ "the rest of the drop phase continues", name, e);
				continue;
			}
			metrics.retentionPartitionDropped();
			totals.partitionsDropped++;
			log.info("Dropped monthly partition {} (fully older than the retention cutoff {})", name, cutoff);
		}
	}

	/**
	 * Phase 3: delete the DEFAULT partition's rows older than the cutoff in bounded
	 * batches. Each batch is its own statement (short locks), the loop stops when a batch
	 * deletes fewer rows than the batch size (nothing left in range) or at the per-pass
	 * batch cap — the remainder then waits for the next pass. Counts every batch into
	 * the pass totals as it happens (review F6), so a failing batch keeps the earlier
	 * batches' counts in the summary.
	 */
	private void deleteExpiredDefaultRows(Instant cutoff, PhaseTotals totals) {
		OffsetDateTime cutoffTimestamp = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
		int batchSize = properties.defaultDeleteBatchSize();
		for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
			int deleted = jdbcTemplate.update("""
					DELETE FROM events_default WHERE id IN
					    (SELECT id FROM events_default WHERE occurred_at < ? LIMIT ?)""",
					cutoffTimestamp, batchSize);
			if (deleted > 0) {
				metrics.retentionDefaultRowsDeleted(deleted);
			}
			totals.defaultRowsDeleted += deleted;
			if (deleted < batchSize) {
				break;
			}
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/** events_YYYY_MM — the bound values are clock-derived months, never anything else. */
	private static String partitionName(YearMonth month) {
		return "events_" + MONTH_NAME_FORMAT.format(month);
	}

	/** UTC wall-clock start of a month; the partition layout's lower/upper bound unit. */
	private static java.time.LocalDateTime startOfMonth(YearMonth month) {
		return month.atDay(1).atStartOfDay();
	}

	/** The same month start as an UTC OffsetDateTime (a bind parameter for range probes). */
	private static OffsetDateTime startOfMonthUtc(YearMonth month) {
		return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
	}

	/** Name -> range-bound expression of every direct child partition of events. */
	private Map<String, String> monthlyPartitionsWithBounds() {
		Map<String, String> partitions = new LinkedHashMap<>();
		jdbcTemplate.query("""
				SELECT c.relname AS name, pg_get_expr(c.relpartbound, c.oid) AS bound
				FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				WHERE i.inhparent = 'public.events'::regclass""",
				resultSet -> {
					partitions.put(resultSet.getString("name"), resultSet.getString("bound"));
				});
		return partitions;
	}

	/**
	 * The upper bound of a monthly partition, read straight from its catalog bound
	 * expression (the precise rule: drop decisions use the real bounds, not the name).
	 * Returns null (and warns) when the expression is not the expected range form, so an
	 * unexpected partition is skipped, never dropped. Package-private (plus
	 * {@link #parseBoundLiteral(String, String)}) so the parsing can be unit-tested
	 * directly (review F3/F8).
	 */
	static Instant upperBound(String partitionName, String boundExpression) {
		Matcher matcher = BOUND_UPPER.matcher(boundExpression);
		if (!matcher.find()) {
			log.warn("Skipping partition {}: its catalog bound [{}] is not a readable range bound",
					partitionName, boundExpression);
			return null;
		}
		return parseBoundLiteral(partitionName, matcher.group(1));
	}

	/**
	 * Parses a catalog bound literal exactly as PostgreSQL's {@code pg_get_expr} renders
	 * it for a timestamptz range bound: {@code YYYY-MM-DD HH:MM:SS[.fraction]<offset>}
	 * with a space separator, where the offset follows the DATABASE SESSION's timezone —
	 * {@code +00} for UTC, whole-hour offsets like {@code -05}, minute offsets like
	 * {@code +05:30} (Asia/Kolkata) or {@code +05:45} (Kathmandu) (review F3), or a bare
	 * {@code Z}. The literal is normalized to strict ISO-8601 (space → {@code T},
	 * {@code +HH} → {@code +HH:00}, {@code Z} → {@code +00:00}) and parsed with
	 * {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME}, so every offset form PostgreSQL
	 * renders is accepted and the bound is a session-timezone-independent instant.
	 * Returns null (with a warning, never an exception to the caller) when the literal
	 * is not a readable timestamp; the caller then keeps the partition.
	 *
	 * <p><b>DateStyle assumption:</b> the literal must be in an ISO {@code DateStyle}
	 * (PostgreSQL's default). A session with {@code DateStyle='SQL, DMY'} renders bounds
	 * as {@code '01/10/2026 05:30:00 IST'}, which is intentionally unreadable here — the
	 * partition is kept (never dropped on a guess) and retention stalls with a warning
	 * until the session uses the default ISO style. The application's own connections use
	 * the server default, so this only bites externally reconfigured sessions.
	 */
	static Instant parseBoundLiteral(String partitionName, String literal) {
		String iso = literal.strip().replace(' ', 'T');
		Matcher offset = BOUND_OFFSET_SUFFIX.matcher(iso);
		if (iso.endsWith("Z") || iso.endsWith("z")) {
			iso = iso.substring(0, iso.length() - 1) + "+00:00";
		}
		else if (offset.find()) {
			iso = iso.substring(0, offset.start()) + offset.group(1) + ":"
					+ (offset.group(2) == null ? "00" : offset.group(2));
		}
		else {
			log.warn("Skipping partition {}: its bound literal [{}] carries no ISO-8601 offset",
					partitionName, literal);
			return null;
		}
		try {
			return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException e) {
			log.warn("Skipping partition {}: its bound literal [{}] did not parse ({})",
					partitionName, literal, e.getMessage());
			return null;
		}
	}

}
