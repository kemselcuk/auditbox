package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Focused context tests for the retention/partition-maintenance properties (M11.2,
 * decision D22), {@link ApplicationContextRunner} style like
 * {@code RateLimitPropertiesTest}: an invalid value with the feature enabled aborts
 * startup with an {@link IllegalStateException} naming the property (no silent clamp),
 * while a disabled feature accepts any value and never enforces it. Runs without Boot
 * auto-configuration or a database.
 */
class RetentionPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RetentionProperties.class)
	static class PropertiesConfiguration {
	}

	@Test
	void boundedValuesBindAsGiven() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=10",
				"auditbox.retention.schedule-cron=0 30 3 * * *")
				.run(context -> {
					assertThat(context).hasNotFailed();
					RetentionProperties properties = context.getBean(RetentionProperties.class);
					assertThat(properties.enabled()).isTrue();
					assertThat(properties.months()).isEqualTo(24);
					assertThat(properties.lookaheadMonths()).isEqualTo(3);
					assertThat(properties.defaultDeleteBatchSize()).isEqualTo(1000);
					assertThat(properties.maxBatchesPerRun()).isEqualTo(10);
					assertThat(properties.scheduleCron()).isEqualTo("0 30 3 * * *");
				});
	}

	@Test
	void zeroMonthsAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=0",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=10")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.retention.months");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("RETENTION_MONTHS");
				});
	}

	@Test
	void aNegativeLookaheadAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=-1",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=10")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.retention.lookahead-months");
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("RETENTION_LOOKAHEAD_MONTHS");
				});
	}

	@Test
	void zeroDeleteBatchSizeAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=0",
				"auditbox.retention.max-batches-per-run=10")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.retention.default-delete-batch-size");
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("RETENTION_DEFAULT_DELETE_BATCH_SIZE");
				});
	}

	@Test
	void zeroMaxBatchesPerRunAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=0")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.retention.max-batches-per-run");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("RETENTION_MAX_BATCHES_PER_RUN");
				});
	}

	@Test
	void aDisabledFeatureAcceptsAnyInvalidOtherwiseValues() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=false",
				"auditbox.retention.months=0",
				"auditbox.retention.lookahead-months=-1",
				"auditbox.retention.default-delete-batch-size=0",
				"auditbox.retention.max-batches-per-run=0")
				.run(context -> {
					assertThat(context).hasNotFailed();
					RetentionProperties properties = context.getBean(RetentionProperties.class);
					assertThat(properties.enabled()).isFalse();
					// Bound, never clamped and never enforced (the job is a no-op).
					assertThat(properties.months()).isEqualTo(0);
					assertThat(properties.lookaheadMonths()).isEqualTo(-1);
					assertThat(properties.defaultDeleteBatchSize()).isEqualTo(0);
					assertThat(properties.maxBatchesPerRun()).isEqualTo(0);
				});
	}

}
