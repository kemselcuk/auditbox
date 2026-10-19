package io.auditbox.events;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.auditbox.metrics.AuditMetrics;

/**
 * Transactional core of the event write path (plan tasks T3.2 and M11.1, decisions D4 and
 * D21; restructured by the M11 review finding F1). It runs the whole
 * lock/pre-check/insert sequence in ONE transaction on ONE database connection:
 *
 * <ol>
 *   <li>{@link #attempt} takes the per-eventId transaction-scoped advisory lock as its
 *       first statement,</li>
 *   <li>under the lock it pre-checks the stored row: equal content hash &rarr; idempotent
 *       replay (200), different &rarr; {@link EventConflictException} (409),</li>
 *   <li>if absent, it inserts and flushes the new row &rarr; created (201).</li>
 * </ol>
 *
 * <h2>Why the unique-violation recovery lives OUTSIDE this transaction</h2>
 *
 * <p>The previous design ran the insert in a second {@code REQUIRES_NEW} transaction on a
 * second connection, so one in-flight write needed two simultaneous pool connections; with
 * N concurrent writes the pool needed N+1 connections and the shipped pool of 32 made 32
 * concurrent writes fail with Hikari timeouts (review finding F1, live-proven). Now the
 * attempt transaction is the only connection a write needs.
 *
 * <p>A unique violation (SQLState 23505 on the unique {@code (occurred_at, event_id)}
 * index) would abort this transaction, so it cannot be caught here without leaving the
 * transaction rollback-only. {@link EventService#record} — which holds no transaction —
 * catches the translated {@code DataIntegrityViolationException} after this transaction
 * has rolled back and calls {@link #resolve} in a fresh read transaction, exactly like a
 * pre-check hit. The two transactions run sequentially, never simultaneously, so the
 * recovery path also needs only one connection.
 *
 * <p>Under the advisory lock the 23505 path is not reachable from the API (the pre-check
 * saw no row and no other request can insert the same eventId until this transaction
 * ends); it remains as the safety net for out-of-band writes. Hash collisions of
 * {@code hashtextextended} only serialize unrelated eventIds for their write duration.
 */
@Service
public class EventWriteService {

	private final EventRepository repository;

	private final AuditMetrics metrics;

	EventWriteService(EventRepository repository, AuditMetrics metrics) {
		this.repository = repository;
		this.metrics = metrics;
	}

	/**
	 * Runs the lock/pre-check/insert sequence for one event in a single transaction.
	 *
	 * @param event the entity to insert when no row exists for its eventId (content hash
	 *              set)
	 * @return the stored event and whether this call created it (201) or replayed it (200)
	 * @throws EventConflictException when the eventId exists with different content (D4/D21)
	 * @throws org.springframework.dao.DataIntegrityViolationException on a unique violation
	 *         (SQLState 23505); the caller resolves it in a fresh transaction
	 */
	@Transactional
	public EventWriteResult attempt(EventEntity event) {
		// D21: the advisory lock must be the first statement of this transaction so all
		// writes for one eventId serialize and the pre-check below runs under the lock.
		repository.lockEventId(event.getEventId());

		// Under the lock: if the row is already stored (committed by a previous request or
		// by a concurrent call that just released the lock), this is a replay or a
		// conflict; never a second insert.
		Optional<EventEntity> existing = repository.findByEventId(event.getEventId());
		if (existing.isPresent()) {
			return resolveAgainstStored(existing.get(), event.getContentHash());
		}

		EventEntity saved = repository.saveAndFlush(event);
		// Milestone 9.4: exactly the 201 outcome counts as created.
		metrics.eventCreated();
		return EventWriteResult.created(saved);
	}

	/**
	 * Resolves a duplicate after a caught unique violation, in its own read-only
	 * transaction that runs after the failed attempt transaction ended (review F1). The
	 * committed winning row must be visible; if it is not, the invariant is broken and
	 * fails loudly.
	 */
	@Transactional(readOnly = true)
	public EventWriteResult resolve(String eventId, String contentHash) {
		EventEntity existing = repository.findByEventId(eventId)
				.orElseThrow(() -> new IllegalStateException(
						"a unique violation (23505) was caught but no stored event was found for eventId " + eventId));
		return resolveAgainstStored(existing, contentHash);
	}

	/**
	 * Resolves a duplicate eventId against the stored row: equal content hash means an
	 * idempotent retry (returns the stored record for a 200 response), a different hash is
	 * a conflict (D4/D21).
	 */
	private EventWriteResult resolveAgainstStored(EventEntity existing, String contentHash) {
		if (existing.getContentHash().equals(contentHash)) {
			// Milestone 9.4: a replay is counted exactly where it is decided (201 counts as
			// created on the insert path above).
			metrics.eventReplayed();
			return EventWriteResult.replayed(existing);
		}
		// Milestone 9.4: the D4 conflict (same eventId, different content → 409) is counted
		// exactly where it is decided, before the handler renders the response.
		metrics.eventConflicted();
		throw new EventConflictException(existing);
	}

}
