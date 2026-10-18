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
 * <p>After a unique violation (SQLState 23505 on {@code uq_events_event_id}) PostgreSQL aborts
 * the current transaction. If the INSERT ran inside an outer {@code @Transactional} scope whose
 * method then caught the exception, Spring would already have marked that transaction
 * rollback-only and the eventual commit would fail with an {@code UnexpectedRollbackException}
 * — the classic pitfall this task explicitly avoids. The insert therefore runs through a
 * {@link TransactionTemplate} with {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} on a
 * separate bean: when the constraint violation surfaces (with {@code saveAndFlush} even inside
 * the transaction callback), the failed transaction is already fully rolled back and the caller
 * ({@link EventService}, which holds no transaction) can safely catch the translated
 * {@link DataIntegrityViolationException} and re-read the stored row.
 *
 * <p>The re-read sees the winning row because PostgreSQL makes a competing INSERT wait on the
 * unique index until the first transaction commits: by the time the loser receives 23505, the
 * winner's row is committed and visible to a fresh read.
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
	 * @throws DataIntegrityViolationException when an event with the same eventId already
	 *                                         exists (D4; SQLState 23505 on
	 *                                         {@code uq_events_event_id}); the failed insert
	 *                                         transaction is already rolled back at this
	 *                                         point
	 */
	public EventEntity insert(EventEntity event) {
		return insertTransaction.execute(status -> repository.saveAndFlush(event));
	}
}
