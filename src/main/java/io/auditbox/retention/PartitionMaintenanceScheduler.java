package io.auditbox.retention;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Thin scheduling wrapper for {@link PartitionMaintenanceService} (M11.2, decision D22).
 * The startup trigger lives here and delegates straight to
 * {@link PartitionMaintenanceService#runMaintenance()} with no logic of its own, so tests
 * drive the service directly with a fixed {@link java.time.Clock}:
 *
 * <ul>
 *   <li>{@link ApplicationReadyEvent} — the once-per-startup pass. Readiness fires after
 *       the whole context refresh, so Flyway has applied the migrations and the
 *       {@code events} schema (with its partitions) exists before the first DDL runs,</li>
 *   <li>the daily cron pass is registered by {@link RetentionConfiguration} as a
 *       {@code SchedulingConfigurer} cron task — conditionally, only when the feature is
 *       enabled (review F4): the previous unconditional {@code @Scheduled} made Spring
 *       parse the cron even for a disabled job, so a bad {@code RETENTION_CRON} failed
 *       the startup of a job that would never run, with an error that did not name the
 *       property.</li>
 * </ul>
 *
 * <p>Both passes share the service's overlap guard; a disabled configuration makes every
 * trigger a no-op inside the service. Phase failures are caught and logged inside the
 * service, so a maintenance problem never fails application startup and never breaks the
 * scheduler thread.
 */
@Component
public class PartitionMaintenanceScheduler {

	private final PartitionMaintenanceService service;

	PartitionMaintenanceScheduler(PartitionMaintenanceService service) {
		this.service = service;
	}

	/** The once-per-startup pass, after Flyway (ApplicationReadyEvent). */
	@EventListener(ApplicationReadyEvent.class)
	public void runAtStartup() {
		service.runMaintenance();
	}

}
