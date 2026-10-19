package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of the in-memory per-source token bucket (milestone 10.2, decision D19),
 * driven by a fake {@link java.util.function.LongSupplier} time source — no sleeps:
 *
 * <ul>
 *   <li>burst exhausts the capacity, further requests are rejected with the exact
 *       whole-second {@code Retry-After} (ceil to the next second, at least 1),</li>
 *   <li>a rejection consumes nothing and does not move the refill clock,</li>
 *   <li>continuous refill restores exactly one token after the configured interval
 *       ({@code 60e9 / requestsPerMinute} nanoseconds),</li>
 *   <li>buckets are isolated per source,</li>
 *   <li>the disabled limiter never rejects and never consumes.</li>
 * </ul>
 */
class RateLimiterTest {

	private static final long TOKEN_UNIT = 1_000_000_000L;

	private long now;

	private RateLimiter limiter(RateLimitProperties properties) {
		return new RateLimiter(properties, () -> now);
	}

	private void assertAllowed(RateLimiter limiter) {
		assertThat(limiter.tryAcquire("source-a")).as("allowed").isEmpty();
	}

	private long rejectedRetryAfter(RateLimiter limiter) {
		OptionalLong retryAfterSeconds = limiter.tryAcquire("source-a");
		assertThat(retryAfterSeconds).as("rejected with Retry-After").isPresent();
		return retryAfterSeconds.getAsLong();
	}

	@Test
	void burstUpToCapacityThenRejectionsWithExactWholeSecondRetryAfter() {
		// 3 rpm = 1 token per 6e9 ns = 20 s; a fully drained bucket owes exactly 1 token.
		RateLimitProperties properties = new RateLimitProperties(true, 3);
		RateLimiter limiter = limiter(properties);

		assertAllowed(limiter);
		assertAllowed(limiter);
		assertAllowed(limiter);
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(20);
	}

	@Test
	void repeatedRejectionsUnderAFrozenClockReturnTheSameRetryAfter() {
		RateLimitProperties properties = new RateLimitProperties(true, 3);
		RateLimiter limiter = limiter(properties);

		assertAllowed(limiter);
		assertAllowed(limiter);
		assertAllowed(limiter);
		long first = rejectedRetryAfter(limiter);

		// Frozen clock, more rejections: identical Retry-After, no consumption, no drift.
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(first);
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(first);
	}

	@Test
	void rejectedRequestsDoNotMoveTheRefillClockSoNoTokenIsLostOrGained() {
		// 1 rpm: interval 6e10 ns; two rejected attempts in between must not grant a token.
		RateLimitProperties properties = new RateLimitProperties(true, 1);
		RateLimiter limiter = limiter(properties);

		assertAllowed(limiter); // the one capacity token, consumed at t=0
		now = 30_000_000_000L; // half the interval: refill = 5e8 sub-units (< 1 token)
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(30); // ceil of 30 s more for 0.5 tokens
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(30); // still nothing consumed

		now = 60_000_000_000L; // exactly one interval later: exactly 1 token restored
		assertAllowed(limiter);
		now = 60_000_000_000L; // frozen again: the bucket is empty until the next interval
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(60);
	}

	@Test
	void continuousRefillRestoresExactlyOneTokenAtTheIntervalBoundary() {
		// 1 rpm → one token per 6e10 ns. The attempt one nanosecond before the boundary
		// must still reject; at the exact boundary exactly one token is available.
		RateLimitProperties properties = new RateLimitProperties(true, 1);
		RateLimiter limiter = limiter(properties);

		assertAllowed(limiter);
		now = 60_000_000_000L - 1;
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(1);
		now = 60_000_000_000L;
		assertAllowed(limiter);
		now = 60_000_000_000L;
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(60);
	}

	@Test
	void bucketsAreIsolatedPerSource() {
		RateLimitProperties properties = new RateLimitProperties(true, 1);
		RateLimiter limiter = limiter(properties);

		assertThat(limiter.tryAcquire("source-a")).isEmpty();
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(60);

		// source-b has its own untouched full bucket.
		assertThat(limiter.tryAcquire("source-b")).isEmpty();
		assertThat(rejectedRetryAfter(limiter)).isEqualTo(60);
	}

	@Test
	void aDisabledLimiterNeverConsumesOrRejects() {
		// enabled=false accepts any requests-per-minute value without validation.
		RateLimitProperties properties = new RateLimitProperties(false, 0);
		RateLimiter limiter = limiter(properties);

		for (int i = 0; i < 1000; i++) {
			assertThat(limiter.tryAcquire("source-a")).isEmpty();
		}
		assertThat(limiter.enabled()).isFalse();
	}
}
