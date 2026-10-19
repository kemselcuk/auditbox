package io.auditbox.events;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Performs the event INSERT in its own transaction boundary (plan task T3.2).
 *
 * <h2>Why PROPAGATION_REQUIRES_NEW</h2>
 *
 * <p>After a unique violation (SQLState 23505 — since the M11.1 partitioning this can come
 * from the unique {@code (occurred_at, event_id)} index) PostgreSQL aborts the current
 * transaction. If the INSERT ran inside the outer {@code @Transactional} scope of
 * {@link EventService#record} whose method then caught the exception, Spring would already
 * have marked that transaction rollback-only and the eventual commit would fail with an
 * {@code UnexpectedRollbackException} — the classic pitfall this task explicitly avoids. The
 * insert therefore runs through a {@link TransactionTemplate} with
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} on a separate bean: when the
 * constraint violation surfaces (with {@code saveAndFlush} even inside the transaction
 * callback), the failed transaction is already fully rolled back and the caller can safely
 * catch the translated {@link DataIntegrityViolationException} and re-read the stored row
 * while its own (outer, advisory-lock-holding) transaction stays intact.
 *
 * <p>The outer transaction of {@code EventService.record} holds the per-eventId advisory
 * lock (D21) on its own connection; this insert runs on a second connection in parallel.
 * That is why the connection pool needs headroom above the peak number of concurrent
 * record requests (see maximum-pool-size in application.yml).
 *
 * <p>Under the advisory lock the caller's pre-check already saw no stored row, so a 23505
 * on this insert is a safety-net case (e.g. a write that bypassed the lock, D21); the re-read
 * sees the winning row because PostgreSQL makes a competing INSERT wait on the unique index
 * until the first transaction commits: by the time the loser receives 23505, the winner's
 * row is committed and visible to a fresh read.
 */
@Service
public class EventInsertService {

	private final TransactionTemplate insertTransaction;
	private final EventRepository repository;

	EventInsertService(PlatformTransactionManager transactionManager, EventRepository repository) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.insertTransaction = template;
		this.repository = repository;
	}

	/**
	 * Inserts the event in a dedicated transaction and returns the persisted entity with the
	 * database-generated values ({@code id}, {@code receivedAt}) filled in.
	 *
	 * @throws DataIntegrityViolationException when an identical (occurredAt, eventId) pair
	 *                                         already exists (D4/D21; SQLState 23505 on the
	 *                                         unique {@code (occurred_at, event_id)} index);
	 *                                         the failed insert transaction is already
	 *                                         rolled back at this point
	 */
	public EventEntity insert(EventEntity event) {
		return insertTransaction.execute(status -> repository.saveAndFlush(event));
	}
}
