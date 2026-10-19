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
 *       {@code occurred_at} values,</li>
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
 * or a bound literal. The bound literals are absolute UTC offsets, so they do not depend
 * on the database session's timezone. Drop decisions read the partition bounds from the
 * PostgreSQL catalog instead of trusting the name, and a partition whose bound cannot be
 * parsed is skipped (kept) with a warning — never dropped on a guess.
 *
 * <p>An in-process {@link AtomicBoolean} guards against overlapping passes (the startup
 * trigger and the cron share it): an overlapping trigger is skipped, not queued. A phase
 * failure is caught, logged with its phase name and does not abort the remaining phases,
 * so one broken step cannot block retention progress; the summary counts only what the
 * pass actually did. With {@code enabled=false} the pass returns immediately: no DDL, no
 * deletes, no counter increments. Counters (M11.2): {@code auditbox.retention.
 * partitions.created}, {@code auditbox.retention.partitions.dropped},
 * {@code auditbox.retention.default.rows.deleted}. Nothing key-related can appear in
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
	 * Parses a catalog bound literal like '2026-10-01 00:00:00+00' (fractional seconds
	 * optional, session rendered offset accepted in any ISO-8601 form). Rendering follows
	 * the database session's DateStyle/TimeZone; anything else than this common ISO form
	 * fails parsing and the partition is skipped (kept) with a warning.
	 */
	private static final DateTimeFormatter CATALOG_BOUND_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]X", Locale.ROOT);

	private final JdbcTemplate jdbcTemplate;

	private final RetentionProperties properties;

	private final AuditMetrics metrics;

	/** The single time source for every decision; tests inject a fixed clock. */
	private final Clock clock;

	/** Overlap guard, shared by the startup pass and the cron pass. */
	private final AtomicBoolean running = new AtomicBoolean();

	PartitionMaintenanceService(JdbcTemplate jdbcTemplate, RetentionProperties properties,
			AuditMetrics metrics, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
	}

	/** What one maintenance pass actually did; also the shape of the logged summary. */
	public record MaintenanceSummary(int partitionsCreated, int partitionsDropped, long defaultRowsDeleted) {
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
			return new MaintenanceSummary(0, 0, 0);
		}
		if (!running.compareAndSet(false, true)) {
			log.warn("Partition maintenance is already running in this process; skipping the overlapping trigger");
			return new MaintenanceSummary(0, 0, 0);
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

		int created = 0;
		int dropped = 0;
		long deleted = 0;
		try {
			created = ensureLookaheadPartitions(currentMonth, monthlyPartitionsWithBounds().keySet());
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: creating the lookahead window failed", e);
		}
		try {
			dropped = dropExpiredPartitions(cutoff);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: dropping expired partitions failed", e);
		}
		try {
			deleted = deleteExpiredDefaultRows(cutoff);
		}
		catch (RuntimeException e) {
			log.error("Partition maintenance: deleting expired DEFAULT-partition rows failed", e);
		}

		// Concise summary, no key material involved anywhere (schema objects and counts only).
		log.info("Partition maintenance done: {} partition(s) created, {} partition(s) dropped, "
				+ "{} DEFAULT-partition row(s) deleted", created, dropped, deleted);
		return new MaintenanceSummary(created, dropped, deleted);
	}

	/**
	 * Phase 1: for every month current..current+lookahead (inclusive), create the missing
	 * monthly partition with the V2-style bounds. Existing partitions are left untouched,
	 * so a pass that finds the whole window in place creates nothing (idempotent).
	 */
	private int ensureLookaheadPartitions(YearMonth currentMonth, Set<String> existingPartitions) {
		int created = 0;
		for (int offset = 0; offset <= properties.lookaheadMonths(); offset++) {
			YearMonth month = currentMonth.plusMonths(offset);
			String partitionName = partitionName(month);
			if (existingPartitions.contains(partitionName)) {
				continue;
			}
			createPartition(partitionName, month);
			metrics.retentionPartitionCreated();
			created++;
			log.info("Created monthly partition {} for window [{}, {})", partitionName,
					startOfMonth(month), startOfMonth(month.plusMonths(1)));
		}
		return created;
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
	 * Phase 2: drop every monthly partition whose upper bound (read from the catalog, not
	 * derived from the name) is at or before the cutoff. See the class javadoc for the
	 * exact rule and the rows-removed-by-drop semantics.
	 */
	private int dropExpiredPartitions(Instant cutoff) {
		int dropped = 0;
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
			// No IF EXISTS: the pass decided this table is droppable; a concurrent
			// drop by another replica would fail this phase (caught per phase) instead
			// of silently miscounting (D22 multi-instance note).
			jdbcTemplate.execute("DROP TABLE " + name);
			metrics.retentionPartitionDropped();
			dropped++;
			log.info("Dropped monthly partition {} (fully older than the retention cutoff {})", name, cutoff);
		}
		return dropped;
	}

	/**
	 * Phase 3: delete the DEFAULT partition's rows older than the cutoff in bounded
	 * batches. Each batch is its own statement (short locks), the loop stops when a batch
	 * deletes fewer rows than the batch size (nothing left in range) or at the per-pass
	 * batch cap — the remainder then waits for the next pass.
	 */
	private long deleteExpiredDefaultRows(Instant cutoff) {
		OffsetDateTime cutoffTimestamp = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
		int batchSize = properties.defaultDeleteBatchSize();
		long total = 0;
		for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
			int deleted = jdbcTemplate.update("""
					DELETE FROM events_default WHERE id IN
					    (SELECT id FROM events_default WHERE occurred_at < ? LIMIT ?)""",
					cutoffTimestamp, batchSize);
			if (deleted > 0) {
				metrics.retentionDefaultRowsDeleted(deleted);
			}
			total += deleted;
			if (deleted < batchSize) {
				break;
			}
		}
		return total;
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
	 * unexpected partition is skipped, never dropped.
	 */
	private static Instant upperBound(String partitionName, String boundExpression) {
		Matcher matcher = BOUND_UPPER.matcher(boundExpression);
		if (!matcher.find()) {
			log.warn("Skipping partition {}: its catalog bound [{}] is not a readable range bound",
					partitionName, boundExpression);
			return null;
		}
		try {
			return OffsetDateTime.parse(matcher.group(1), CATALOG_BOUND_FORMAT).toInstant();
		}
		catch (DateTimeParseException e) {
			log.warn("Skipping partition {}: its catalog bound [{}] did not parse ({})",
					partitionName, boundExpression, e.getMessage());
			return null;
		}
	}

}
