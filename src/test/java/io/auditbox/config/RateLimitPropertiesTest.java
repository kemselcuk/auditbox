package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Focused context tests for the rate-limit properties (milestone 10.2, decision D19),
 * {@link ApplicationContextRunner} style like {@link ApiKeyProdRequirementTest}: an invalid
 * {@code auditbox.rate-limit.requests-per-minute} with the limiter enabled aborts startup
 * with an {@link IllegalStateException} naming the property (no silent clamp), while a
 * disabled limiter accepts any value and never limits. Runs without Boot
 * auto-configuration or a database.
 */
class RateLimitPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RateLimitProperties.class)
	static class PropertiesConfiguration {
	}

	@Test
	void boundedValuesBindAsGiven() {
		runner.withPropertyValues(
				"auditbox.rate-limit.enabled=true",
				"auditbox.rate-limit.requests-per-minute=300")
				.run(context -> {
					assertThat(context).hasNotFailed();
					RateLimitProperties properties = context.getBean(RateLimitProperties.class);
					assertThat(properties.enabled()).isTrue();
					assertThat(properties.requestsPerMinute()).isEqualTo(300);
				});
	}

	@Test
	void zeroRequestsPerMinuteWithTheLimiterEnabledAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.rate-limit.enabled=true",
				"auditbox.rate-limit.requests-per-minute=0")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.rate-limit.requests-per-minute");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("RATE_LIMIT_REQUESTS_PER_MINUTE");
				});
	}

	@Test
	void aNegativeRequestsPerMinuteWithTheLimiterEnabledAbortsStartupToo() {
		runner.withPropertyValues(
				"auditbox.rate-limit.enabled=true",
				"auditbox.rate-limit.requests-per-minute=-1")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.rate-limit.requests-per-minute");
				});
	}

	@Test
	void aDisabledLimiterAcceptsAnyRequestsPerMinuteValue() {
		runner.withPropertyValues(
				"auditbox.rate-limit.enabled=false",
				"auditbox.rate-limit.requests-per-minute=0")
				.run(context -> {
					assertThat(context).hasNotFailed();
					RateLimitProperties properties = context.getBean(RateLimitProperties.class);
					assertThat(properties.enabled()).isFalse();
					assertThat(properties.requestsPerMinute()).isEqualTo(0); // bound, never clamped
				});
	}

	@Test
	void aValidValueStartsTheContext() {
		runner.withPropertyValues(
				"auditbox.rate-limit.enabled=true",
				"auditbox.rate-limit.requests-per-minute=1")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(RateLimitProperties.class).requestsPerMinute()).isEqualTo(1);
				});
	}
}
