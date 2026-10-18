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
 *       that required the static API key and was rejected by the authentication entry point
 *       (review F2); the key value itself is never used or logged, only the structural
 *       reason.</li>
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
}
