package io.auditbox.events;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link EventEntity} (Stages 3, 4 and M11.1). Beyond standard
 * CRUD the idempotency flow (D4/D21) needs a lookup by the client-generated eventId — with the
 * partitioned schema the lookup scans every partition's {@code ix_events_event_id} index
 * (documented trade-off, D21) — and the transaction-scoped advisory lock that serializes all
 * writes for one eventId (the replacement of the V1 global {@code uq_events_event_id}
 * constraint, which a partitioned table cannot keep on {@code event_id} alone).
 * Stage 4 (T4.1) adds the {@link JpaSpecificationExecutor} contract: the listing
 * of GET /api/v1/events builds its filtered, keyset-ordered page query as a
 * {@link org.springframework.data.jpa.domain.Specification} and executes it through the
 * fluent {@code findBy(spec, query -> ...limit(...).all())} API (exactly one SELECT, no
 * COUNT — see {@link EventQueryService}).
 */
public interface EventRepository extends JpaRepository<EventEntity, Long>, JpaSpecificationExecutor<EventEntity> {

	Optional<EventEntity> findByEventId(String eventId);

	/**
	 * Takes the per-eventId advisory lock (M11.1, D21): {@code pg_advisory_xact_lock} is
	 * transaction-scoped, so the lock is held until the caller's transaction ends (commit or
	 * rollback) and blocks every concurrent write for the same eventId during that window.
	 * The key is {@code hashtextextended(eventId, 0)}; a hash collision merely serializes two
	 * unrelated eventIds for their write duration — acceptable (documented in D21).
	 *
	 * <p>Must be the FIRST database statement of the caller's transaction:
	 * {@link EventService#record} guarantees this by calling it right after its
	 * {@code @Transactional} boundary opens.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:eventId, 0))", nativeQuery = true)
	void lockEventId(@Param("eventId") String eventId);
}
