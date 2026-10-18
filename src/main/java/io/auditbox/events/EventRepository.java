package io.auditbox.events;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link EventEntity} (Stage 3). Beyond standard CRUD the
 * idempotency flow (D4) only needs a lookup by the client-generated eventId; it runs in its
 * own read transaction so it can re-read the winning row after a unique-violation rollback.
 */
public interface EventRepository extends JpaRepository<EventEntity, Long> {

	Optional<EventEntity> findByEventId(String eventId);
}
