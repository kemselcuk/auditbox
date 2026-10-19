package io.auditbox.retention;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Wiring for the retention/partition-maintenance job (M11.2, decision D22), kept separate
 * from the security and rate-limit configurations on purpose. It
 *
 * <ul>
 *   <li>enables Spring's {@code @Scheduled} execution for the daily cron pass,</li>
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
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfiguration {

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
