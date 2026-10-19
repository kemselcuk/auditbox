package io.auditbox.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention and partition-maintenance properties (M11.2, decision D22), bound from the
 * {@code auditbox.retention} prefix (defaults are supplied by the base
 * {@code application.yml} as environment-variable placeholders):
 *
 * <ul>
 *   <li>{@code enabled} — bound from {@code RETENTION_ENABLED}, default {@code true}; the
 *       master switch. While it is {@code false} every maintenance pass is a no-op: no
 *       DDL, no deletes, no counter increments,</li>
 *   <li>{@code months} — bound from {@code RETENTION_MONTHS}, default 24; the retention
 *       window. A monthly partition whose entire range is older than now(UTC) −
 *       {@code months} is dropped; older rows of the DEFAULT partition are deleted.
 *       When enabled the value must be at least 1: a smaller value aborts startup with
 *       an {@link IllegalStateException} naming the property (never silently clamped),</li>
 *   <li>{@code lookahead-months} — bound from {@code RETENTION_LOOKAHEAD_MONTHS},
 *       default 3; months ahead of the current month (inclusive) that the job keeps
 *       ready with an empty {@code events_YYYY_MM} partition. When enabled the value
 *       must be at least 0,</li>
 *   <li>{@code default-delete-batch-size} — bound from
 *       {@code RETENTION_DEFAULT_DELETE_BATCH_SIZE}, default 1000; row count of one
 *       DELETE batch against the DEFAULT partition — and of one move batch out of it
 *       into a new monthly partition (review F2),</li>
 *   <li>{@code max-batches-per-run} — bound from {@code RETENTION_MAX_BATCHES_PER_RUN},
 *       default 10; the per-pass cap on those batches (for both the expired-rows DELETE
 *       and the ensure-phase move, review F2), so one pass never runs an unbounded
 *       statement. Both values must be at least 1 when enabled.</li>
 *   <li>{@code schedule-cron} — bound from {@code RETENTION_CRON}, default
 *       {@code 0 30 3 * * *} (daily 03:30 server time); parsed by the
 *       {@code @Scheduled} registration itself — an unusable cron aborts startup with
 *       its own error.</li>
 * </ul>
 *
 * <p>Validation runs on every bind via this record's canonical constructor and only when
 * the feature is enabled (a disabled job never executes any of the values), matching the
 * {@code auditbox.rate-limit} pattern: a bad value fails startup in every profile instead
 * of being clamped.
 */
@ConfigurationProperties(prefix = "auditbox.retention")
public record RetentionProperties(boolean enabled, int months, int lookaheadMonths,
		int defaultDeleteBatchSize, int maxBatchesPerRun, String scheduleCron) {

	public RetentionProperties {
		if (enabled) {
			if (months < 1) {
				throw new IllegalStateException("Invalid auditbox.retention.months=" + months
						+ " (RETENTION_MONTHS): when auditbox.retention.enabled is true"
						+ " the value must be at least 1");
			}
			if (lookaheadMonths < 0) {
				throw new IllegalStateException("Invalid auditbox.retention.lookahead-months="
						+ lookaheadMonths + " (RETENTION_LOOKAHEAD_MONTHS): when"
						+ " auditbox.retention.enabled is true the value must be at least 0");
			}
			if (defaultDeleteBatchSize < 1) {
				throw new IllegalStateException("Invalid auditbox.retention.default-delete-batch-size="
						+ defaultDeleteBatchSize + " (RETENTION_DEFAULT_DELETE_BATCH_SIZE): when"
						+ " auditbox.retention.enabled is true the value must be at least 1");
			}
			if (maxBatchesPerRun < 1) {
				throw new IllegalStateException("Invalid auditbox.retention.max-batches-per-run="
						+ maxBatchesPerRun + " (RETENTION_MAX_BATCHES_PER_RUN): when"
						+ " auditbox.retention.enabled is true the value must be at least 1");
			}
		}
	}

}
