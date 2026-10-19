package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.auditbox.metrics.AuditMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The disabled feature (M11.2, decision D22): with
 * {@code auditbox.retention.enabled=false}, the startup pass AND every direct pass are
 * strict no-ops — no DDL, no deletes, no counter increments — even with a fixed clock
 * at which an enabled job would have plenty of work (the fake current month is beyond
 * the V2 initial window).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"auditbox.retention.enabled=false",
		"auditbox.retention.months=24",
		"auditbox.retention.lookahead-months=3" })
@Testcontainers
@ActiveProfiles("test")
class PartitionMaintenanceDisabledIntegrationTest {

	/** Pinned PostgreSQL image; keep in sync with docker-compose.yml. */
	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	/** A clock an ENABLED job would have to work hard at: current real month + 8. */
	private static final Instant FUTURE_INSTANT = YearMonth.now(ZoneOffset.UTC).plusMonths(8)
			.atDay(15).atTime(10, 0).atOffset(ZoneOffset.UTC).toInstant();

	@Autowired
	PartitionMaintenanceService service;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MeterRegistry meterRegistry;

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			// The job must be a no-op REGARDLESS of what this clock says.
			return Clock.fixed(FUTURE_INSTANT, ZoneOffset.UTC);
		}

	}

	@Test
	void disabledMakesEverythingANoOp() {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);

		// Nothing ran at startup: the lookahead window beyond the V2 one was NOT created.
		assertThat(monthlyPartitionNames()).doesNotContain(
				partitionName(current.plusMonths(8)), partitionName(current.plusMonths(11)));
		// The V2 initial window (current - 25 .. + 3) AND the default partition are
		// exactly as the migration left them: nothing was dropped, nothing recreated.
		assertThat(monthlyPartitionNames()).hasSize(29);
		for (int offset = -25; offset <= 3; offset++) {
			assertThat(monthlyPartitionNames()).contains(partitionName(current.plusMonths(offset)));
		}
		assertThat(partitionNames()).contains("events_default");

		// No counter was ever incremented, neither at startup ...
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(0);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(0);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(0);

		// ... and a direct pass stays a no-op too (the clock predates nothing here —
		// the disabled check happens before any time is read).
		PartitionMaintenanceService.MaintenanceSummary summary = service.runMaintenance();
		assertThat(summary).isEqualTo(new PartitionMaintenanceService.MaintenanceSummary(0, 0, 0));
		assertThat(monthlyPartitionNames()).hasSize(29);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_CREATED)).isEqualTo(0);
		assertThat(count(AuditMetrics.RETENTION_PARTITIONS_DROPPED)).isEqualTo(0);
		assertThat(count(AuditMetrics.RETENTION_DEFAULT_ROWS_DELETED)).isEqualTo(0);
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private double count(String name) {
		Counter counter = meterRegistry.find(name).counter();
		return counter == null ? 0.0 : counter.count();
	}

	private List<String> partitionNames() {
		return jdbcTemplate.queryForList("""
				SELECT c.relname FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				WHERE i.inhparent = 'public.events'::regclass""", String.class);
	}

	private List<String> monthlyPartitionNames() {
		return partitionNames().stream().filter(name -> name.matches("events_\\d{4}_\\d{2}")).toList();
	}

	private static String partitionName(YearMonth month) {
		return "events_"
				+ java.time.format.DateTimeFormatter.ofPattern("uuuu_MM", java.util.Locale.ROOT).format(month);
	}

}
