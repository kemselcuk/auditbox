package io.auditbox.config;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory per-source token-bucket rate limiter (milestone 10.2, decision D19). One
 * bucket per authenticated SOURCE (the principal name of the D18 registry) in a
 * {@link ConcurrentHashMap} — the key set is bounded by the configured sources, nothing
 * grows unboundedly. Single-instance only: the buckets live in this JVM's memory, so the
 * limit is per process; a multi-instance deployment would need a shared store and is
 * deliberately out of scope.
 *
 * <p>Bucket semantics: capacity = {@code requests-per-minute} tokens (a source can burst
 * up to the limit instantly), refilled continuously at {@code requests-per-minute / 60}
 * tokens per second. All arithmetic is in integer sub-units (1 token = 1e9 sub-units)
 * over an injectable time source ({@link System#nanoTime} in production), so tests advance
 * a fake clock instead of sleeping. Refills are accounted exactly: the refill clock only
 * advances by the time actually converted into tokens, so no fraction of a token is lost
 * between calls.
 *
 * <p>The limiter applies only to authenticated requests: the filter decides what counts and
 * this class just consumes tokens for the sources it is given. A disabled limiter never
 * consumes tokens and never rejects.
 */
class RateLimiter {

	/** Sub-units in one token; integer math avoids floating-point refill drift. */
	private static final long TOKEN_UNIT = 1_000_000_000L;

	private final RateLimitProperties properties;

	/** Injected time source in nanoseconds; monotonic increasing is assumed. */
	private final LongSupplier nanoTime;

	/** One bucket per source name; sources come from the bounded D18 registry. */
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	RateLimiter(RateLimitProperties properties) {
		this(properties, System::nanoTime);
	}

	/** Test seam: a fake {@code LongSupplier} replaces the wall clock. */
	RateLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
		this.properties = properties;
		this.nanoTime = nanoTime;
	}

	boolean enabled() {
		return properties.enabled();
	}

	/**
	 * Consumes one token for the given source.
	 *
	 * @param source the authenticated principal name (never null)
	 * @return empty when a token was consumed (request allowed); present with the whole
	 *         number of seconds (at least 1) until one token is available (request rejected)
	 */
	OptionalLong tryAcquire(String source) {
		if (!properties.enabled()) {
			return OptionalLong.empty();
		}
		int perMinute = properties.requestsPerMinute();
		// computeIfAbsent: bounded by the configured source names; a lookup-only race
		// cannot create a bucket for a name that never authenticates.
		return buckets.computeIfAbsent(source, key -> new Bucket(perMinute)).tryAcquire(perMinute, nanoTime.getAsLong());
	}

	/** Mutable per-source state; guarded so a refill and a consume are one step. */
	private static final class Bucket {

		/** Marks a bucket no request has ever touched; refill then just starts its clock. */
		private static final long NEVER_USED = Long.MIN_VALUE;

		private final long capacityNano;

		private long tokensNano;

		private long lastNanos;

		Bucket(int perMinute) {
			this.capacityNano = perMinute * (long) TOKEN_UNIT;
			this.tokensNano = capacityNano; // full bucket at creation: an instant burst is allowed
			this.lastNanos = NEVER_USED;
		}

		/**
		 * @param perMinute the configured requests per minute (the refill rate denominator)
		 * @param now the current time-source value in nanoseconds
		 */
		OptionalLong tryAcquire(int perMinute, long now) {
			synchronized (this) {
				refill(perMinute, now);
				if (tokensNano >= TOKEN_UNIT) {
					tokensNano -= TOKEN_UNIT;
					return OptionalLong.empty();
				}
				long deficit = TOKEN_UNIT - tokensNano;
				// Seconds = ceil(deficit tokens / (perMinute/60 tokens per second)) in
				// sub-units: perMinute * TOKEN_UNIT sub-units per 60 seconds. deficit <=
				// TOKEN_UNIT, so the product cannot overflow (max ~6e10 + perMinute*1e9).
				long seconds = (deficit * 60L + (long) perMinute * TOKEN_UNIT - 1) / ((long) perMinute * TOKEN_UNIT);
				return OptionalLong.of(Math.max(1, seconds));
			}
		}

		private void refill(int perMinute, long now) {
			if (lastNanos == NEVER_USED) {
				lastNanos = now; // the bucket starts full; no historical credit is granted
				return;
			}
			long elapsed = now - lastNanos;
			if (elapsed <= 0) {
				return; // no time has passed (non-monotonic clocks are treated the same)
			}
			if (tokensNano >= capacityNano || perMinute <= 0) {
				lastNanos = now; // full buckets discard the idle time; no banking of history
				return;
			}
			// Sub-unit refill rate: perMinute/60 sub-units per nanosecond, i.e.
			// elapsed * perMinute / 60 sub-units over the elapsed time.
			if (elapsed > Long.MAX_VALUE / (long) perMinute) {
				tokensNano = capacityNano; // saturation: the idle time exceeded the capacity horizon
				lastNanos = now;
				return;
			}
			long refilled = elapsed * (long) perMinute / 60L;
			if (tokensNano + refilled >= capacityNano) {
				tokensNano = capacityNano;
				lastNanos = now; // surplus idle time is discarded once the bucket is full
				return;
			}
			tokensNano += refilled;
			// Continuous refill must not lose sub-second fractions: move the clock by only
			// the time actually converted into tokens; an untouched late fraction of the
			// elapsed time stays pending for the next call.
			lastNanos += refilled * 60L / (long) perMinute;
		}
	}
}
