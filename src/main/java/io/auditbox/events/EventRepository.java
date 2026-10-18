package io.auditbox.events;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data JPA repository for {@link EventEntity} (Stages 3 and 4). Beyond standard CRUD
 * the idempotency flow (D4) only needs a lookup by the client-generated eventId; it runs in
 * its own read transaction so it can re-read the winning row after a unique-violation
 * rollback. Stage 4 (T4.1) adds the {@link JpaSpecificationExecutor} contract: the listing
 * of GET /api/v1/events builds its filtered, keyset-ordered page query as a
 * {@link org.springframework.data.jpa.domain.Specification} and executes it through the
 * fluent {@code findBy(spec, query -> ...limit(...).all())} API (exactly one SELECT, no
 * COUNT — see {@link EventQueryService}).
 */
public interface EventRepository extends JpaRepository<EventEntity, Long>, JpaSpecificationExecutor<EventEntity> {

	Optional<EventEntity> findByEventId(String eventId);
}
