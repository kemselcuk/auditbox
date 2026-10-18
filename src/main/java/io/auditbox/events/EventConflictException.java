package io.auditbox.events;

/**
 * Thrown when an event with the same eventId is submitted again with different content (D4):
 * the events API answers 409 Conflict with the stored record in the {@code existingEvent}
 * Problem Details extension (see {@link EventApiExceptionHandler}).
 */
public class EventConflictException extends RuntimeException {

	private final transient EventEntity existingEvent;

	public EventConflictException(EventEntity existingEvent) {
		super("an event with eventId '" + existingEvent.getEventId() + "' already exists with different content");
		this.existingEvent = existingEvent;
	}

	public EventEntity getExistingEvent() {
		return existingEvent;
	}
}
