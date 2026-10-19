package io.auditbox.retention;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;

/**
 * Wiring for the retention/partition-maintenance job (M11.2, decision D22), kept separate
 * from the security and rate-limit configurations on purpose. It
 *
 * <ul>
 *   <li>enables Spring's scheduling and registers the daily cron pass CONDITIONALLY
 *       (review F4): as a {@link SchedulingConfigurer} it adds the cron task only when
 *       {@code auditbox.retention.enabled} is true, parsing the cron there — with the
 *       feature disabled the value is never parsed and no task is registered, so an
 *       invalid cron cannot fail a disabled job's startup,</li>
 *   <li>binds the {@code auditbox.retention.*} properties
 *       ({@link RetentionProperties}, validated on every bind),</li>
 *   <li>provides the job's {@link Clock}: the system UTC clock in production. The
 *       service converts every instant it uses to UTC (D10), so the host zone is
 *       irrelevant; tests replace this bean with a fixed clock,</li>
 *   <li>provides the {@link TaskScheduler} for the cron: one daemon thread. The daemon
 *       flag is deliberate — the maintenance job must never keep the JVM (or a test
 *       fork) alive at shutdown. (The 30-second fork-exit stall that showed up when
 *       M11.2 landed was NOT this thread: it was the sum of Spring Boot's graceful
 *       shutdown waits across the ~20 cached test contexts, fixed test-only in
 *       {@code application-test.yml} with {@code server.shutdown=immediate}.)</li>
 * </ul>
 *
 * <p>The {@link ObjectProvider} indirection for the service is deliberate: the
 * configuration's constructor must not force the service (and its {@code Clock})
 * into creation — the service is only resolved when the enabled cron task is
 * registered, which happens after the whole context is refreshed.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfiguration implements SchedulingConfigurer {

	private final RetentionProperties properties;

	private final ObjectProvider<PartitionMaintenanceService> maintenanceService;

	RetentionConfiguration(RetentionProperties properties,
			ObjectProvider<PartitionMaintenanceService> maintenanceService) {
		this.properties = properties;
		this.maintenanceService = maintenanceService;
	}

	/**
	 * Registers the daily cron pass (review F4). The previous unconditional
	 * {@code @Scheduled(cron = "${auditbox.retention.schedule-cron}")} made Spring parse
	 * the cron at startup even when the feature was disabled — a bad value failed an
	 * unnecessary startup, and the framework's error did not name the property. Here the
	 * enabled job parses its cron inside a try/catch that rethrows with the property and
	 * environment names; a disabled job registers nothing at all.
	 */
	@Override
	public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
		if (!properties.enabled()) {
			// Disabled (D22): the job is a strict no-op, so its schedule is never
			// registered — and the cron value is never parsed.
			return;
		}
		try {
			CronExpression.parse(properties.scheduleCron());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid auditbox.retention.schedule-cron=\""
					+ properties.scheduleCron() + "\" (RETENTION_CRON): when"
					+ " auditbox.retention.enabled is true the value must be a parsable Spring"
					+ " cron expression (" + e.getMessage() + ")", e);
		}
		taskRegistrar.addCronTask(this.maintenanceService.getObject()::runMaintenance,
				properties.scheduleCron());
	}

	@Bean
	Clock retentionClock() {
		return Clock.systemUTC();
	}

	@Bean
	TaskScheduler retentionTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("retention-");
		scheduler.setPoolSize(1);
		scheduler.setDaemon(true);
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}

}
