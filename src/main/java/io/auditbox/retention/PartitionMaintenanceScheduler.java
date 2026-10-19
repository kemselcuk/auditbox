package io.auditbox.retention;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduling wrapper for {@link PartitionMaintenanceService} (M11.2, decision D22).
 * Both triggers delegate straight to {@link PartitionMaintenanceService#runMaintenance()}
 * and carry no logic of their own, so tests drive the service directly with a fixed
 * {@link java.time.Clock}:
 *
 * <ul>
 *   <li>{@link ApplicationReadyEvent} — the once-per-startup pass. Readiness fires after
 *       the whole context refresh, so Flyway has applied the migrations and the
 *       {@code events} schema (with its partitions) exists before the first DDL runs,</li>
 *   <li>{@code @Scheduled} cron — bound from {@code auditbox.retention.schedule-cron}
 *       ({@code RETENTION_CRON}, default daily 03:30 server time).</li>
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

	/** The daily pass on the configured cron. */
	@Scheduled(cron = "${auditbox.retention.schedule-cron}")
	public void runScheduled() {
		service.runMaintenance();
	}

}
