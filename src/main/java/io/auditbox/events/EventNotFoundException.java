package io.auditbox.events;

/**
 * A GET /api/v1/events/{eventId} lookup found no stored row for the given eventId
 * (Milestone 9.3). The controller-scoped Problem Details advice
 * ({@link EventApiExceptionHandler}) maps it to a 404 with a generic detail — the
 * response never distinguishes "never existed" from anything else beyond the plain fact,
 * and carries no internals.
 */
public class EventNotFoundException extends RuntimeException {

	public EventNotFoundException() {
		super("Event not found");
	}
}
