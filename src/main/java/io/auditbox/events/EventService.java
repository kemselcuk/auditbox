package io.auditbox.events;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/**
 * Write-side orchestration of POST /api/v1/events (plan tasks T3.1/T3.2, decision D4):
 *
 * <ol>
 *   <li>hash the content with {@link EventContentHasher} (inputs already validated by
 *       Bean Validation, T3.1),</li>
 *   <li>insert in a dedicated transaction ({@link EventInsertService}) — success is a new
 *       event (201),</li>
 *   <li>on a unique violation of {@code uq_events_event_id}, re-read the stored row in a
 *       fresh read and compare content hashes: identical &rarr; idempotent replay (200),
 *       different &rarr; {@link EventConflictException} (409 with the stored record).</li>
 * </ol>
 *
 * <p>This class and the controller deliberately carry no transaction: the insert transaction
 * is closed before the exception is caught here, so the rollback-only pitfall described on
 * {@link EventInsertService} cannot occur. The re-read runs in its own transaction and sees
 * the committed winning row (the unique-index wait of PostgreSQL guarantees the winner has
 * committed by the time the loser observes 23505).
 */
@Service
public class EventService {

	private final EventInsertService insertService;
	private final EventRepository repository;

	EventService(EventInsertService insertService, EventRepository repository) {
		this.insertService = insertService;
		this.repository = repository;
	}

	/**
	 * Records one event and reports whether it was created or replayed.
	 *
	 * @param request a validated request (Bean Validation ran on the controller boundary);
	 *                {@code occurredAt} is guaranteed to parse strictly (D10)
	 * @return the stored event and whether this call created it
	 * @throws EventConflictException when the eventId exists with different content (D4)
	 */
	public EventWriteResult record(CreateEventRequest request) {
		JsonNode metadata = normalizeMetadata(request.metadata());
		String contentHash = EventContentHasher.hash(request.eventId(), request.userId(), request.eventType(),
				request.source(), request.occurredAt(), metadata);

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
		event.setMetadata(metadata);
		event.setContentHash(contentHash);

		try {
			return EventWriteResult.created(insertService.insert(event));
		}
		catch (DataIntegrityViolationException e) {
			if (!isUniqueEventIdViolation(e)) {
				// A different integrity problem is not a duplicate eventId (D4).
				throw e;
			}
			// uq_events_event_id is the only unique constraint on the table, so a 23505 is by
			// definition a duplicate eventId (D4). The failed insert transaction is already
			// rolled back (EventInsertService); the stored row is the committed winner.
			return EventWriteResult.replayed(storedEvent(request.eventId(), contentHash));
		}
	}

	/**
	 * Confirms that an integrity violation is the {@code uq_events_event_id} unique violation
	 * (SQLState 23505) and not some other constraint problem. Spring ORM 7 translates
	 * Hibernate's {@code ConstraintViolationException} to a generic
	 * {@link DataIntegrityViolationException} (not a {@code DuplicateKeyException}), so the
	 * SQL state is taken from the cause chain.
	 */
	private static boolean isUniqueEventIdViolation(DataIntegrityViolationException e) {
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
	 * Resolves a duplicate eventId against the stored row: equal content hash means an
	 * idempotent retry (returns the stored record for a 200 response), a different hash is a
	 * conflict (D4).
	 */
	private EventEntity storedEvent(String eventId, String contentHash) {
		EventEntity existing = repository.findByEventId(eventId)
				.orElseThrow(() -> new IllegalStateException(
						"uq_events_event_id was violated but no stored event was found for eventId " + eventId));
		if (existing.getContentHash().equals(contentHash)) {
			return existing;
		}
		throw new EventConflictException(existing);
	}

	/**
	 * D10: absent metadata and an explicit JSON null are the same content — both are stored
	 * as SQL {@code NULL} and both hash to JSON {@code null}.
	 */
	private static JsonNode normalizeMetadata(JsonNode metadata) {
		return metadata == null || metadata.isNull() ? null : metadata;
	}
}
