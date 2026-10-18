package io.auditbox.events;

/**
 * Thrown when a query parameter of GET /api/v1/events violates the read contract (plan task
 * T4.1): a malformed cursor, a non-ISO-8601 {@code occurredFrom}/{@code occurredTo} value, an
 * inverted date range or a page size outside 1..100. The events API answers 400 Bad Request
 * with the message as the Problem Details detail (see {@link EventApiExceptionHandler}).
 * Unlike the Bean Validation failures of the POST request body there is no {@code errors}
 * extension, because each query parameter carries at most one value.
 */
public class InvalidEventQueryException extends RuntimeException {

	public InvalidEventQueryException(String message) {
		super(message);
	}
}
