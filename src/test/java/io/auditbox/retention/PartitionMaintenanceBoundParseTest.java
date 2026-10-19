package io.auditbox.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Review F3: the catalog bound parser must accept EVERY ISO-8601 offset PostgreSQL can
 * render for a timestamptz bound — the literal follows the DATABASE session's timezone,
 * so a minute offset like {@code +05:30} (Asia/Kolkata) or {@code +05:45} (Kathmandu) is
 * exactly as legal as the UTC default {@code +00}. Before the fix the one-letter offset
 * pattern parsed only whole-hour offsets, so under such a session zone every bound failed
 * to parse, every partition was skipped (kept) and retention silently stopped. The
 * malformed literals return null (with a logged warning) — never an exception to the
 * caller.
 */
class PartitionMaintenanceBoundParseTest {

	private static final String PARTITION = "events_2026_10";

	@Test
	void parsesTheUtcBoundLiteralPostgreSQLRendersByDefault() {
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 00:00:00+00");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
	}

	@Test
	void parsesAWholeHourNegativeOffsetLiteral() {
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 00:00:00-05");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T05:00:00Z"));
	}

	@Test
	void parsesAMinuteOffsetLiteralLikeAsiaKolkata() {
		// The F3 case: under SET TIME ZONE 'Asia/Kolkata' PostgreSQL renders the very
		// same UTC month start as 05:30 local time with a +05:30 offset.
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 05:30:00+05:30");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
	}

	@Test
	void parsesAKathmanduStyleMinuteOffsetLiteral() {
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 05:45:00+05:45");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
	}

	@Test
	void parsesAZuluOffsetLiteral() {
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 00:00:00Z");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
	}

	@Test
	void parsesAFractionalSecondLiteral() {
		Instant parsed = PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 05:30:00.5+05:30");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00.500Z"));
	}

	@Test
	void aMalformedLiteralReturnsNullAndNeverThrows() {
		assertThatCode(() -> {
			assertThat(PartitionMaintenanceService.parseBoundLiteral(PARTITION, "not a timestamp")).isNull();
			assertThat(PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-10-01 00:00:00")).isNull();
			assertThat(PartitionMaintenanceService.parseBoundLiteral(PARTITION, "2026-13-45 99:99:99+00")).isNull();
			assertThat(PartitionMaintenanceService.parseBoundLiteral(PARTITION, "")).isNull();
		}).doesNotThrowAnyException();
	}

	@Test
	void upperBoundReadsTheFullCatalogBoundExpressionWithAnyIsoOffset() {
		Instant parsed = PartitionMaintenanceService.upperBound(PARTITION,
				"FOR VALUES FROM ('2026-09-01 05:30:00+05:30') TO ('2026-10-01 05:30:00+05:30')");
		assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
	}

	@Test
	void upperBoundReturnsNullForAnUnexpectedBoundExpression() {
		// The DEFAULT partition's bound — not a range expression — is skipped (kept).
		assertThat(PartitionMaintenanceService.upperBound("events_default", "DEFAULT")).isNull();
		assertThat(PartitionMaintenanceService.upperBound(PARTITION, "junk")).isNull();
	}

}
