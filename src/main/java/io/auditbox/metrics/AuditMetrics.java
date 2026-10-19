package io.auditbox.metrics;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Domain counters of the events service (plan Milestone 9.4), registered against the
 * auto-configured {@link MeterRegistry} and exposed through the (key-protected)
 * {@code /actuator/metrics} endpoint:
 *
 * <ul>
 *   <li>{@code auditbox.events.created} — a POST stored a new event (201),</li>
 *   <li>{@code auditbox.events.replayed} — an identical retry returned the stored record
 *       (200, D4),</li>
 *   <li>{@code auditbox.events.conflicts} — same eventId with different content (409, D4),</li>
 *   <li>{@code auditbox.auth.failures} — tag {@code reason=missing|invalid} for a request
 *       that required the API key and was rejected by the authentication entry point
 *       (review F2); the key value itself is never used or logged, only the structural
 *       reason,</li>
 *   <li>{@code auditbox.rate.limited} — a request rejected with 429 by the per-source rate
 *       limiter (Milestone 10.2, D19). No tag: the source name is legitimate authenticated
 *       data, but caller-chosen values must not drive the counter cardinality.</li>
 *   <li>{@code auditbox.retention.partitions.created} — a missing monthly partition in the
 *       lookahead window was created by the partition-maintenance job (M11.2, D22),</li>
 *   <li>{@code auditbox.retention.partitions.dropped} — a monthly partition fully older
 *       than the retention window was dropped by the job; dropping the partition removes
 *       its rows by design (this IS the retention policy, D22),</li>
 *   <li>{@code auditbox.retention.default.rows.deleted} — rows older than the retention
 *       window deleted from the DEFAULT partition in bounded batches (M11.2, D22).</li>
 * </ul>
 *
 * <p><code>Counter.increment()</code> keeps absolute counts — Micrometer drops increments
 * from much larger application timers anyway; keep them as counters because they must be
 * reported to systems that aggregate on their own. The tag value set is closed
 * ({@code reason} carries only two values), so the counter cardinality is bounded.
 */
@Component
public class AuditMetrics {

	public static final String EVENTS_CREATED = "auditbox.events.created";

	public static final String EVENTS_REPLAYED = "auditbox.events.replayed";

	public static final String EVENTS_CONFLICTS = "auditbox.events.conflicts";

	public static final String AUTH_FAILURES = "auditbox.auth.failures";

	/** One request rejected with 429 by the per-source rate limiter (D19); no tags. */
	public static final String RATE_LIMITED = "auditbox.rate.limited";

	/** One monthly partition created in the lookahead window by the maintenance job (D22); no tags. */
	public static final String RETENTION_PARTITIONS_CREATED = "auditbox.retention.partitions.created";

	/** One monthly partition dropped by retention; its rows are removed with it, by design (D22). */
	public static final String RETENTION_PARTITIONS_DROPPED = "auditbox.retention.partitions.dropped";

	/** Rows older than the retention window deleted from the DEFAULT partition, bounded batches (D22). */
	public static final String RETENTION_DEFAULT_ROWS_DELETED = "auditbox.retention.default.rows.deleted";

	/** The only tag key: completed by one of the two structural reason values below. */
	public static final String AUTH_FAILURE_REASON_TAG = "reason";

	/** The request carried no X-API-Key header at all. */
	public static final String AUTH_FAILURE_REASON_MISSING = "missing";

	/** The request carried a header value that did not match the configured key. */
	public static final String AUTH_FAILURE_REASON_INVALID = "invalid";

	private final MeterRegistry registry;

	AuditMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public void eventCreated() {
		registry.counter(EVENTS_CREATED).increment();
	}

	public void eventReplayed() {
		registry.counter(EVENTS_REPLAYED).increment();
	}

	public void eventConflicted() {
		registry.counter(EVENTS_CONFLICTS).increment();
	}

	/**
	 * Counts one failed API-key check. The tag is structural (missing vs. invalid header):
	 * the submitted key or any part of it must never reach a metric name, tag, or log.
	 */
	public void authFailure(String reason) {
		registry.counter(AUTH_FAILURES, AUTH_FAILURE_REASON_TAG, reason).increment();
	}

	/**
	 * Counts one 429 emitted by the rate limiter. Deliberately untagged: per-source
	 * counting would let caller-chosen source names drive the counter cardinality (D19).
	 */
	public void rateLimited() {
		registry.counter(RATE_LIMITED).increment();
	}

	/** One monthly lookahead partition created by the maintenance job (D22). */
	public void retentionPartitionCreated() {
		registry.counter(RETENTION_PARTITIONS_CREATED).increment();
	}

	/** One retention drop: the partition and its rows are gone by design (D22). */
	public void retentionPartitionDropped() {
		registry.counter(RETENTION_PARTITIONS_DROPPED).increment();
	}

	/**
	 * Counts {@code rows} rows deleted from the DEFAULT partition by one bounded batch.
	 * Batched so a pass that dies mid-way still reports exactly the rows it deleted.
	 */
	public void retentionDefaultRowsDeleted(long rows) {
		registry.counter(RETENTION_DEFAULT_ROWS_DELETED).increment(rows);
	}
}
