package io.auditbox.events;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/**
 * Write-side orchestration of POST /api/v1/events (plan tasks T3.1/T3.2 and M11.1,
 * decision D4 with the D21 partitioning; restructured by the M11 review finding F1):
 *
 * <ol>
 *   <li>hash the content with {@link EventContentHasher} (inputs already validated by
 *       Bean Validation, T3.1),</li>
 *   <li>hand the entity to {@link EventWriteService#attempt} — one transaction, one
 *       connection — which takes the per-eventId advisory lock, pre-checks the stored row
 *       (equal hash &rarr; 200 replay, different &rarr; 409 conflict) and otherwise inserts
 *       (201),</li>
 *   <li>if the attempt still hits the unique {@code (occurred_at, event_id)} index
 *       (SQLState 23505, only reachable for writes that bypassed the advisory lock), this
 *       class — which holds NO transaction, so nothing is rollback-only — catches the
 *       violation and resolves the committed winner in a fresh read transaction
 *       ({@link EventWriteService#resolve}), returning the same 200/409 as a pre-check
 *       hit.</li>
 * </ol>
 *
 * <p>The advisory lock restores the D4 guarantee the partitioned schema took away: the
 * replaced {@code uq_events_event_id} index now includes {@code occurred_at}, so the
 * database alone would allow the same eventId twice in different months. Under the lock
 * no second request can insert before the winner commits, and a request that arrives
 * later blocks on the lock and then sees the committed row in its pre-check — so the
 * same eventId can never produce two rows. A second request for the same eventId
 * therefore never reaches the insert path via the API.
 *
 * <p>Connection model (review F1): the attempt transaction is the only connection a
 * write needs while it runs, and the recovery transaction runs strictly after the failed
 * attempt rolled back — never simultaneously. No pool size can therefore deadlock the
 * write path; the pool only bounds concurrent write throughput.
 */
@Service
public class EventService {

	private final EventWriteService writeService;

	EventService(EventWriteService writeService) {
		this.writeService = writeService;
	}

	/**
	 * Records one event and reports whether it was created or replayed.
	 *
	 * @param request a validated request (Bean Validation ran on the controller boundary);
	 *                {@code occurredAt} is guaranteed to parse strictly (D10)
	 * @return the stored event and whether this call created it
	 * @throws EventConflictException when the eventId exists with different content (D4/D21)
	 */
	public EventWriteResult record(CreateEventRequest request) {
		String contentHash = contentHashOf(request);
		try {
			return writeService.attempt(buildEvent(request, contentHash));
		}
		catch (DataIntegrityViolationException e) {
			if (!isUniqueViolation(e)) {
				// A different integrity problem is not a duplicate eventId (D4).
				throw e;
			}
			// Safety net (review F1): the attempt transaction has already rolled back at
			// this point, so this method holds no rollback-only state. SQLState 23505 can
			// come from the unique (occurred_at, event_id) index; under the advisory lock
			// it is not reachable from the API (the pre-check saw no row and no one else
			// can insert the same eventId until the attempt transaction ends) — it guards
			// writes that bypassed the lock. Resolve the committed winner in a fresh read
			// transaction, exactly like a pre-check hit.
			return writeService.resolve(request.eventId(), contentHash);
		}
	}

	private static String contentHashOf(CreateEventRequest request) {
		return EventContentHasher.hash(request.eventId(), request.userId(), request.eventType(),
				request.source(), request.occurredAt(), normalizeMetadata(request.metadata()));
	}

	private static EventEntity buildEvent(CreateEventRequest request, String contentHash) {
		EventEntity event = new EventEntity();
		event.setEventId(request.eventId());
		event.setUserId(request.userId());
		event.setEventType(request.eventType());
		event.setSource(request.source());
		// Truncate to microseconds and normalize to UTC before storing (D10): PostgreSQL
		// timestamptz stores microseconds, the hash is normalized to the same precision and
		// instant, and the 201 response must render the same UTC form a later re-read (200)
		// returns — so a retry echoing the API value is identical content.
		event.setOccurredAt(OffsetDateTime.parse(request.occurredAt()).truncatedTo(ChronoUnit.MICROS)
				.withOffsetSameInstant(ZoneOffset.UTC));
		event.setMetadata(normalizeMetadata(request.metadata()));
		event.setContentHash(contentHash);
		return event;
	}

	/**
	 * Confirms that an integrity violation is a unique violation (SQLState 23505) and not
	 * some other constraint problem. Spring ORM 7 translates Hibernate's
	 * {@code ConstraintViolationException} to a generic {@link DataIntegrityViolationException}
	 * (not a {@code DuplicateKeyException}), so the SQL state is taken from the cause chain.
	 */
	private static boolean isUniqueViolation(DataIntegrityViolationException e) {
		Throwable cause = e.getCause();
		while (cause != null) {
			if (cause instanceof SQLException sqlException) {
				return "23505".equals(sqlException.getSQLState());
			}
			cause = cause.getCause();
		}
		return false;
	}

	/**
	 * D10: absent metadata and an explicit JSON null are the same content — both are stored
	 * as SQL {@code NULL} and both hash to JSON {@code null}.
	 */
	private static JsonNode normalizeMetadata(JsonNode metadata) {
		return metadata == null || metadata.isNull() ? null : metadata;
	}

}
