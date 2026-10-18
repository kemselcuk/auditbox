package io.auditbox.events;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Predicate;

/**
 * Read-side orchestration of GET /api/v1/events (plan task T4.1, decisions D2/D3), kept in
 * its own service next to the write-side {@link EventService}: the filters (D2) combine
 * conjunctively and the order is the keyset order of the
 * {@code ix_events_occurred_at_event_id} index (occurredAt DESC, eventId DESC, D3/D11).
 *
 * <h2>Query approach: one SELECT per page, no COUNT (D3)</h2>
 *
 * <p>The page is fetched through the Spring Data JPA 4.1 fluent Specification API,
 * {@code repository.findBy(spec, query -> query.sortBy(...).limit(size + 1).all())}. On the
 * installed spring-data-jpa 4.1.1 the implementation
 * ({@code FetchableFluentQueryBySpecification.all()}) executes exactly one SELECT with
 * {@code TypedQuery.setMaxResults(size + 1)} and never runs a count operation (verified
 * against the shipped bytecode, and asserted in the integration tests via Hibernate
 * statistics). {@code findAll(spec, Pageable)} was rejected because it always issues a
 * COUNT query to fill the {@code Page} total, which D3 forbids; hand-written Criteria API
 * would work but only replicates what the fluent API already does.
 *
 * <p>{@code nextCursor} is emitted whenever a (size + 1)-th row arrives, so detecting the
 * last page needs no extra query either: one statement answers a page request in total.
 */
@Service
public class EventQueryService {

	private final EventRepository repository;

	EventQueryService(EventRepository repository) {
		this.repository = repository;
	}

	/**
	 * Returns the stored record for exactly the given eventId (Milestone 9.3) or throws
	 * {@link EventNotFoundException} (rendered as a 404 Problem Details response with a
	 * generic detail by {@link EventApiExceptionHandler}).
	 */
	public EventResponse get(String eventId) {
		return repository.findByEventId(eventId)
				.map(EventResponse::from)
				.orElseThrow(EventNotFoundException::new);
	}

	/**
	 * Returns one page of at most {@code query.size()} items, newest first, plus the cursor
	 * to the next page when more results exist. The keyset predicate
	 * {@code (occurred_at < :cursorTime) OR (occurred_at = :cursorTime AND event_id < :cursorId)}
	 * selects exactly the rows strictly after the previous page's last item under the
	 * (occurredAt DESC, eventId DESC) order, so pages are stable under concurrent inserts:
	 * a new event can only appear at a position before the cursor (fresh walks) or after
	 * the end, never inside the remaining pages.
	 */
	EventPage list(EventListQuery query) {
		List<EventEntity> fetched = repository.findBy(specification(query), fluent -> fluent
				.sortBy(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("eventId")))
				.limit(query.size() + 1)
				.all());

		boolean hasMore = fetched.size() > query.size();
		List<EventResponse> items = fetched.stream().limit(query.size()).map(EventResponse::from).toList();
		if (!hasMore) {
			return new EventPage(items, null);
		}
		EventResponse last = items.getLast();
		return new EventPage(items, EventCursor.encode(last.occurredAt(), last.eventId()));
	}

	/**
	 * Builds the WHERE clause: optional exact-match filters (D2), optional inclusive
	 * occurredAt bounds and the keyset predicate. All comparisons go through parameter
	 * binding (no string concatenation).
	 */
	private static Specification<EventEntity> specification(EventListQuery query) {
		return (root, q, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (query.userId() != null) {
				predicates.add(cb.equal(root.get("userId"), query.userId()));
			}
			if (query.eventType() != null) {
				predicates.add(cb.equal(root.get("eventType"), query.eventType()));
			}
			if (query.source() != null) {
				predicates.add(cb.equal(root.get("source"), query.source()));
			}
			if (query.occurredFrom() != null) {
				predicates.add(cb.greaterThanOrEqualTo(
						root.<OffsetDateTime>get("occurredAt"), query.occurredFrom()));
			}
			if (query.occurredTo() != null) {
				predicates.add(cb.lessThanOrEqualTo(
						root.<OffsetDateTime>get("occurredAt"), query.occurredTo()));
			}
			if (query.cursor() != null) {
				OffsetDateTime cursorTime = query.cursor().occurredAt();
				String cursorId = query.cursor().eventId();
				predicates.add(cb.or(
						cb.lessThan(root.<OffsetDateTime>get("occurredAt"), cursorTime),
						cb.and(
								cb.equal(root.get("occurredAt"), cursorTime),
								cb.lessThan(root.<String>get("eventId"), cursorId))));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}
}
