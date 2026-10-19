package io.auditbox.events;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.auditbox.metrics.AuditMetrics;

import tools.jackson.databind.JsonNode;

/**
 * Write-side orchestration of POST /api/v1/events (plan tasks T3.1/T3.2 and M11.1,
 * decision D4 with the D21 partitioning):
 * <ol>
 *   <li>hash the content with {@link EventContentHasher} (inputs already validated by
 *       Bean Validation, T3.1),</li>
 *   <li>inside one {@code @Transactional} scope, take the transaction-scoped advisory
 *       lock per eventId as the FIRST statement ({@code pg_advisory_xact_lock}); every
 *       write for the same eventId serializes on it, and it is released when this
 *       transaction commits,</li>
 *   <li>under the lock, BEFORE inserting, look up the stored row by eventId: equal
 *       content hash &rarr; idempotent replay (200), different &rarr;
 *       {@link EventConflictException} (409 with the stored record),</li>
 *   <li>if absent, insert through the dedicated {@link EventInsertService}
 *       ({@code REQUIRES_NEW}) — success is a new event (201). An insert that collides
 *       with the unique {@code (occurred_at, event_id)} index is caught as a safety
 *       net and resolved exactly like a pre-check hit (re-read &rarr; 200/409).</li>
 * </ol>
 *
 * <p>The advisory lock restores the D4 guarantee the partitioned schema took away: the
 * replaced {@code uq_events_event_id} index now includes {@code occurred_at}, so the
 * database alone would allow the same eventId twice in different months. Under the
 * lock no second request can insert before the winner commits (the lock is released
 * at outer commit, after the inner insert transaction committed), and a request that
 * arrives later blocks on the lock and then sees the committed row in its pre-check —
 * so the same eventId can never produce two rows. A second request for the same
 * eventId therefore never reaches the insert path via the API; the 23505 catch is a
 * safety net (e.g. a row written out of band), and SQLState 23505 can now also come
 * from the {@code (occurred_at, event_id)} index.
 *
 * <p>The insert keeps running in its own transaction: {@code REQUIRES_NEW} commits it
 * independently and an inner failure does not mark this outer transaction
 * rollback-only (see {@link EventInsertService}). Hash collisions of
 * {@code hashtextextended} only serialize unrelated eventIds for their write duration.
 */
@Service
public class EventService {

	private final EventInsertService insertService;
	private final EventRepository repository;
	private final AuditMetrics metrics;

	EventService(EventInsertService insertService, EventRepository repository, AuditMetrics metrics) {
		this.insertService = insertService;
		this.repository = repository;
		this.metrics = metrics;
	}

	/**
	 * Records one event and reports whether it was created or replayed.
	 *
	 * @param request a validated request (Bean Validation ran on the controller boundary);
	 *                {@code occurredAt} is guaranteed to parse strictly (D10)
	 * @return the stored event and whether this call created it
	 * @throws EventConflictException when the eventId exists with different content (D4/D21)
	 */
	@Transactional
	public EventWriteResult record(CreateEventRequest request) {
		String contentHash = contentHashOf(request);

		// D21: the advisory lock must be the first statement of this transaction so all
		// writes for one eventId serialize and the pre-check below runs under the lock.
		repository.lockEventId(request.eventId());

		// Under the lock: if the row is already stored (committed by a previous request
		// or by a concurrent call that just released the lock), this is a replay or a
		// conflict; never a second insert.
		Optional<EventEntity> existing = repository.findByEventId(request.eventId());
		if (existing.isPresent()) {
			return resolveAgainstStored(existing.get(), contentHash);
		}

		try {
			EventWriteResult created = EventWriteResult.created(insertService.insert(buildEvent(request, contentHash)));
			// Milestone 9.4: exactly the 201 outcome counts as created.
			metrics.eventCreated();
			return created;
		}
		catch (DataIntegrityViolationException e) {
			if (!isUniqueViolation(e)) {
				// A different integrity problem is not a duplicate eventId (D4).
				throw e;
			}
			// Safety net: SQLState 23505 can come from the unique (occurred_at, event_id)
			// index (D21). Under the advisory lock this is not reachable from the API
			// (the pre-check saw no row and no one else can insert the same eventId until
			// this transaction ends) — it guards writes that bypassed the lock. The failed
			// insert transaction is already rolled back (EventInsertService); the stored
			// row is the committed winner, resolved exactly like a pre-check hit.
			return resolveAgainstStored(storedEvent(request.eventId()), contentHash);
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
	 * Resolves a pre-check hit against the stored row: equal content hash means an
	 * idempotent retry (returns the stored record for a 200 response), a different hash is
	 * a conflict (D4/D21).
	 */
	private EventWriteResult resolveAgainstStored(EventEntity existing, String contentHash) {
		if (existing.getContentHash().equals(contentHash)) {
			// Milestone 9.4: a replay is counted exactly where it is decided (201 counts as
			// created on the insert path below).
			metrics.eventReplayed();
			return EventWriteResult.replayed(existing);
		}
		// Milestone 9.4: the D4 conflict (same eventId, different content → 409) is counted
		// exactly where it is decided, before the handler renders the response.
		metrics.eventConflicted();
		throw new EventConflictException(existing);
	}

	/**
	 * Re-reads the stored row after a caught unique violation; only called when the write
	 * path reached the insert with no pre-check hit, so the committed winner must be
	 * visible; if it is not, the invariant is broken and fails loudly.
	 */
	private EventEntity storedEvent(String eventId) {
		return repository.findByEventId(eventId)
				.orElseThrow(() -> new IllegalStateException(
						"a unique violation (23505) was caught but no stored event was found for eventId " + eventId));
	}

	/**
	 * D10: absent metadata and an explicit JSON null are the same content — both are stored
	 * as SQL {@code NULL} and both hash to JSON {@code null}.
	 */
	private static JsonNode normalizeMetadata(JsonNode metadata) {
		return metadata == null || metadata.isNull() ? null : metadata;
	}
}
