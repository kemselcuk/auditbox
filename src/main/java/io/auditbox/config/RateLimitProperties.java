package io.auditbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limiter properties (milestone 10.2, decision D19), bound from the
 * {@code auditbox.rate-limit} prefix (defaults are supplied by the base
 * {@code application.yml} as environment-variable placeholders):
 *
 * <ul>
 *   <li>{@code enabled} — bound from {@code RATE_LIMIT_ENABLED}, default {@code true}; the
 *       master switch, with no limiter and no {@code auditbox.rate.limited} increments
 *       while it is {@code false},</li>
 *   <li>{@code requests-per-minute} — bound from {@code RATE_LIMIT_REQUESTS_PER_MINUTE},
 *       default 300; the capacity of every per-source token bucket. When the limiter is
 *       enabled the value must be at least 1: a smaller value aborts startup with an
 *       {@link IllegalStateException} naming the property (never silently clamped). When
 *       the limiter is disabled any value binds and is never enforced.</li>
 * </ul>
 *
 * <p>Hand-rolled per-source token buckets, in memory only: no new dependency, table, or
 * service (D19). The validation runs on every bind via this record's canonical
 * constructor, so a bad value fails startup in every profile.
 */
@ConfigurationProperties(prefix = "auditbox.rate-limit")
public record RateLimitProperties(boolean enabled, int requestsPerMinute) {

	public RateLimitProperties {
		if (enabled && requestsPerMinute < 1) {
			throw new IllegalStateException("Invalid auditbox.rate-limit.requests-per-minute=" + requestsPerMinute
					+ " (RATE_LIMIT_REQUESTS_PER_MINUTE): when auditbox.rate-limit.enabled is true"
					+ " the value must be at least 1");
		}
	}
}
