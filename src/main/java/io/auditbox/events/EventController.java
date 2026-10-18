package io.auditbox.events;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * HTTP API of the events service (plan tasks T3.1/T3.2 and T4.1): POST /api/v1/events with
 * the D4 idempotency semantics — 201 new event, 200 identical retry, 409 different content —
 * and GET /api/v1/events with filters and stable keyset pagination (D2/D3). Both handlers
 * share the controller-scoped Problem Details advice ({@link EventApiExceptionHandler}).
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Event ingestion: idempotent per eventId (D4); listing: keyset pagination (D3)")
public class EventController {

	private final EventService eventService;

	private final EventQueryService eventQueryService;

	EventController(EventService eventService, EventQueryService eventQueryService) {
		this.eventService = eventService;
		this.eventQueryService = eventQueryService;
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

	/**
	 * Lists one page of events, newest first (T4.1, D2/D3). Query parameters arrive as raw
	 * strings and are parsed and validated by {@link EventListQuery#parse}: any malformed
	 * cursor, invalid date bound or out-of-range size becomes a 400 Problem Details
	 * response (via {@link InvalidEventQueryException}) instead of a framework type-mismatch
	 * error. The temporary basic-auth chain still applies (Stage 5 replaces it, D5).
	 */
	@Operation(summary = "List events", description = """
			Returns one page of events, newest first (occurredAt DESC, eventId DESC). Filters
			combine conjunctively with exact match; occurredFrom and occurredTo are inclusive
			bounds in strict ISO-8601 with an offset. Pagination is keyset-based: pass the
			previous response's nextCursor verbatim as the cursor parameter; nextCursor is null
			on the last page. A page request executes a single SELECT of at most size + 1 rows;
			there is no total count (D3).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of events (nextCursor null on the last page)",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = EventPage.class))),
			@ApiResponse(responseCode = "400", description = "Malformed cursor, invalid occurredFrom/occurredTo, "
					+ "occurredFrom after occurredTo, or size outside 1..100",
					content = @Content(mediaType = "application/problem+json")) })
	@GetMapping
	public EventPage list(
			@Parameter(description = "Exact-match filter on userId", example = "user-42")
			@RequestParam(required = false) String userId,
			@Parameter(description = "Exact-match filter on eventType", example = "payment.settled")
			@RequestParam(required = false) String eventType,
			@Parameter(description = "Exact-match filter on source", example = "core-banking")
			@RequestParam(required = false) String source,
			@Parameter(description = "Inclusive lower bound on occurredAt (strict ISO-8601 with an offset)",
					example = "2026-10-18T00:00:00Z",
					schema = @Schema(type = "string", format = "date-time"))
			@RequestParam(required = false) String occurredFrom,
			@Parameter(description = "Inclusive upper bound on occurredAt (strict ISO-8601 with an offset)",
					example = "2026-10-18T23:59:59Z",
					schema = @Schema(type = "string", format = "date-time"))
			@RequestParam(required = false) String occurredTo,
			@Parameter(description = "Opaque continuation cursor: echo the previous page's nextCursor verbatim "
					+ "(base64url without padding over the last item's occurredAt and eventId)",
					example = "eyJvY2N1cnJlZEF0IjoiMjAyNi0xMC0xOFQxMjowMDowMC4xMjM0NTZaIiwiZXZlbnRJZCI6IjhiMGMwZjYxLTk3"
							+ "YTEtNGUwYS05ZjU2LTJmN2QzZjNiMmMxMCJ9")
			@RequestParam(required = false) String cursor,
			@Parameter(description = "Page size, 1..100 (default 20)", example = "20",
					schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
			@RequestParam(defaultValue = "20") String size) {
		return eventQueryService.list(
				EventListQuery.parse(userId, eventType, source, occurredFrom, occurredTo, cursor, size));
	}
}
