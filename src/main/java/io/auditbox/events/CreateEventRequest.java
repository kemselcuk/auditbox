package io.auditbox.events;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * Request body of POST /api/v1/events (plan task T3.1, decision D1) with its Bean Validation
 * rules. Field sizes mirror the 128-character guidance of D1; {@code metadata} may be any
 * JSON value and an absent field is equivalent to an explicit JSON null (D10).
 *
 * <p>{@code occurredAt} is carried as a string and format-checked by
 * {@link Iso8601OffsetDateTime} so a malformed value surfaces as a field error in the 400
 * Problem Details response; {@link EventService} parses it after validation.
 *
 * <p>The metadata tree is read by the Spring MVC mapper with
 * {@code DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS} (application.yml, D10) so client
 * decimals reach the hash and storage exactly, and its numbers are bounded by
 * {@link ValidMetadata}: no metadata number may exceed the supported digit counts of the
 * canonical decimal form (review round 2, F1/F2).
 */
@Schema(description = "Event ingestion request (D1). The eventId is the idempotency key (D4).")
public record CreateEventRequest(

		@NotBlank(message = "eventId must not be blank")
		@Size(max = 128, message = "eventId must not exceed 128 characters")
		@Schema(description = "Client-generated event identifier (UUID recommended); unique per event (D4)",
				maxLength = 128, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "8b0c0f61-97a1-4e0a-9f56-2f7d3f3b2c10")
		String eventId,

		@NotBlank(message = "userId must not be blank")
		@Size(max = 128, message = "userId must not exceed 128 characters")
		@Schema(description = "User the event belongs to", maxLength = 128,
				requiredMode = Schema.RequiredMode.REQUIRED, example = "user-42")
		String userId,

		@NotBlank(message = "eventType must not be blank")
		@Size(max = 128, message = "eventType must not exceed 128 characters")
		@Schema(description = "Event type", maxLength = 128, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "payment.settled")
		String eventType,

		@NotBlank(message = "source must not be blank")
		@Size(max = 128, message = "source must not exceed 128 characters")
		@Schema(description = "System that produced the event", maxLength = 128,
				requiredMode = Schema.RequiredMode.REQUIRED, example = "core-banking")
		String source,

		@NotNull(message = "occurredAt must not be null")
		@Iso8601OffsetDateTime
		@Schema(description = "When the event occurred; strict ISO-8601 with a UTC offset (Z or +/-hh:mm)",
				type = "string", format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-10-18T12:00:00Z")
		String occurredAt,

		@ValidMetadata
		@Schema(description = "Optional metadata: any JSON value. Absent and explicit null are the same content "
				+ "(D10). Client decimals are preserved exactly, bounded to the supported canonical "
				+ "digit counts (1000 integer / 1000 fractional digits).",
				example = "{\"amount\":125.50,\"currency\":\"EUR\"}")
		JsonNode metadata) {
}
