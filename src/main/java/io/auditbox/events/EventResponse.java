package io.auditbox.events;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.databind.JsonNode;

/**
 * Stored event record as returned by the API: the body of 201 (new event) and 200
 * (idempotent retry), and the {@code existingEvent} extension of the 409 Problem Details
 * response (D4). Exposes exactly the stored fields a client may see — the internal
 * {@code content_hash} is intentionally not part of any response.
 */
@Schema(description = "Stored event record")
public record EventResponse(

		@Schema(description = "Server-assigned row id", example = "123")
		Long id,

		@Schema(description = "Client-generated event identifier (as submitted)")
		String eventId,

		@Schema(description = "User the event belongs to")
		String userId,

		@Schema(description = "Event type")
		String eventType,

		@Schema(description = "System that produced the event")
		String source,

		@Schema(description = "When the event occurred (UTC instant of the submitted offset date-time, "
				+ "microsecond precision)", format = "date-time")
		OffsetDateTime occurredAt,

		@Schema(description = "Metadata as stored (any JSON value; null when the event has no metadata)")
		JsonNode metadata,

		@Schema(description = "Service receipt time set by the server (database clock at insert)",
				format = "date-time")
		OffsetDateTime receivedAt) {

	/**
	 * Maps a persisted {@link EventEntity} (including its database-generated
	 * {@code receivedAt}) to the API representation.
	 */
	public static EventResponse from(EventEntity entity) {
		return new EventResponse(entity.getId(), entity.getEventId(), entity.getUserId(), entity.getEventType(),
				entity.getSource(), entity.getOccurredAt(), entity.getMetadata(), entity.getReceivedAt());
	}
}
