package io.auditbox.events;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * HTTP write API of the events service (plan tasks T3.1/T3.2): POST /api/v1/events with the
 * D4 idempotency semantics — 201 new event, 200 identical retry, 409 different content.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Event ingestion: idempotent per eventId (D4)")
public class EventController {

	private final EventService eventService;

	EventController(EventService eventService) {
		this.eventService = eventService;
	}

	/**
	 * Records one event. Status semantics (D4): 201 for a new eventId, 200 for an idempotent
	 * retry (same eventId, identical content), 409 for a conflict (same eventId, different
	 * content) with the stored record in the {@code existingEvent} extension.
	 */
	@Operation(summary = "Record an event", description = """
			Stores a transaction event keyed by the client-generated eventId and returns the stored
			record. Submitting the same eventId again with identical content returns the stored record
			(200, idempotent retry). Submitting the same eventId with different content is rejected
			with 409 and the stored record in the existingEvent extension. receivedAt is set by the
			server and is never read from the request.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Event stored",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = EventResponse.class))),
			@ApiResponse(responseCode = "200", description = "Idempotent retry: same eventId with identical content",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = EventResponse.class))),
			@ApiResponse(responseCode = "400", description = "Validation failed (field errors in the errors extension)",
					content = @Content(mediaType = "application/problem+json")),
			@ApiResponse(responseCode = "409", description = "Conflict: same eventId with different content "
					+ "(stored record in the existingEvent extension)",
					content = @Content(mediaType = "application/problem+json")) })
	@PostMapping
	public ResponseEntity<EventResponse> record(@Valid @RequestBody CreateEventRequest request) {
		EventWriteResult result = eventService.record(request);
		EventResponse response = EventResponse.from(result.event());
		return result.created()
				? ResponseEntity.status(HttpStatus.CREATED).body(response)
				: ResponseEntity.ok(response);
	}
}
