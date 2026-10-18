package io.auditbox.events;

/**
 * Outcome of the idempotent insert flow (D4): the persisted event plus whether this request
 * created the row ({@code created = true} &rarr; 201) or replayed an identical stored row
 * ({@code created = false} &rarr; 200). The controller maps the flag to the status code.
 */
public record EventWriteResult(boolean created, EventEntity event) {

	public static EventWriteResult created(EventEntity event) {
		return new EventWriteResult(true, event);
	}

	public static EventWriteResult replayed(EventEntity event) {
		return new EventWriteResult(false, event);
	}
}
