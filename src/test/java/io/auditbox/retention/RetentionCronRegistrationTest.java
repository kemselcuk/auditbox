package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import io.auditbox.metrics.AuditMetrics;

/**
 * Review F4: the daily cron pass is registered CONDITIONALLY. The previous unconditional
 * {@code @Scheduled(cron = "${auditbox.retention.schedule-cron}")} made Spring parse the
 * cron at startup even when the feature was disabled — a bad value failed the startup of
 * a job that would never run, and the framework error did not name the property. Now:
 *
 * <ul>
 *   <li>disabled + invalid cron → the app starts fine (the value is never parsed),</li>
 *   <li>enabled + invalid cron → startup aborts with an exception naming
 *       {@code auditbox.retention.schedule-cron} and {@code RETENTION_CRON},</li>
 *   <li>enabled + valid cron → exactly one cron task is registered.</li>
 * </ul>
 *
 * <p>{@link ApplicationContextRunner} style like {@link RetentionPropertiesTest} — no
 * database; the service collaborator is a Mockito stub because the runner context has
 * none of the real wiring.
 */
class RetentionCronRegistrationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(RetentionConfiguration.class, MaintenanceServiceConfiguration.class);

	@Test
	void aDisabledJobStartsFineWithAnInvalidCron() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=false",
				"auditbox.retention.schedule-cron=not-a-cron")
				.run(context -> {
					assertThat(context).hasNotFailed();
					// And no task was registered for the disabled job.
					assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();
				});
	}

	@Test
	void anEnabledJobWithAnInvalidCronAbortsStartupNamingTheProperty() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=10",
				"auditbox.retention.schedule-cron=not-a-cron")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("auditbox.retention.schedule-cron")
							.hasStackTraceContaining("RETENTION_CRON");
				});
	}

	@Test
	void anEnabledJobWithAValidCronRegistersExactlyOneCronTask() {
		runner.withPropertyValues(
				"auditbox.retention.enabled=true",
				"auditbox.retention.months=24",
				"auditbox.retention.lookahead-months=3",
				"auditbox.retention.default-delete-batch-size=1000",
				"auditbox.retention.max-batches-per-run=10",
				"auditbox.retention.schedule-cron=0 30 3 * * *")
				.run(context -> {
					assertThat(context).hasNotFailed();
					Set<ScheduledTask> tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
					assertThat(tasks).hasSize(1);
					assertThat(tasks).allSatisfy(task -> {
						assertThat(task.getTask()).isInstanceOf(CronTask.class);
						assertThat(((CronTask) task.getTask()).getExpression()).isEqualTo("0 30 3 * * *");
					});
				});
	}

	/**
	 * Provides the maintenance service the enabled cron task delegates to. All
	 * collaborators are stubs — the cron task itself never fires in these tests (its
	 * next execution is tomorrow at 03:30).
	 */
	@Configuration(proxyBeanMethods = false)
	static class MaintenanceServiceConfiguration {

		@Bean
		PartitionMaintenanceService partitionMaintenanceService(RetentionProperties properties) {
			return new PartitionMaintenanceService(Mockito.mock(JdbcTemplate.class), properties,
					Mockito.mock(AuditMetrics.class), Clock.systemUTC());
		}
	}

}
