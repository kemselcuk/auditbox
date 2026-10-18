package io.auditbox.events;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parsed and validated query parameters of GET /api/v1/events (plan task T4.1, decisions
 * D2/D3). The controller receives every parameter as a raw string and this factory applies
 * the read contract:
 *
 * <ul>
 *   <li>{@code userId}, {@code eventType}, {@code source} — optional exact-match filters,</li>
 *   <li>{@code occurredFrom}/{@code occurredTo} — optional inclusive bounds, parsed with the
 *       same strict ISO-8601-with-offset rule as the write-side {@code occurredAt}
 *       ({@link OffsetDateTime#parse}, D10): a value without an offset is invalid,</li>
 *   <li>{@code cursor} — decoded by {@link EventCursor};</li>
 *   <li>{@code size} — page size, default 20, bounded to 1..100; an absent or empty value
 *       falls back to the default.</li>
 * </ul>
 *
 * <p>All parameters are parsed manually instead of binding typed parameters (e.g.
 * {@code Integer} or {@code OffsetDateTime}): a framework conversion failure would surface
 * outside the documented Problem Details handling of this API, while every malformed value
 * here deterministically becomes a 400 via {@link InvalidEventQueryException} with a detail
 * naming the offending parameter (T4.1).
 */
record EventListQuery(
		String userId,
		String eventType,
		String source,
		OffsetDateTime occurredFrom,
		OffsetDateTime occurredTo,
		EventCursor.Position cursor,
		int size) {

	static final int DEFAULT_SIZE = 20;
	static final int MIN_SIZE = 1;
	static final int MAX_SIZE = 100;

	/**
	 * Parses the raw query parameters; throws {@link InvalidEventQueryException} on the
	 * first violation.
	 */
	static EventListQuery parse(String userId, String eventType, String source,
			String occurredFrom, String occurredTo, String cursor, String size) {
		int parsedSize;
		if (size == null || size.isBlank()) {
			parsedSize = DEFAULT_SIZE;
		}
		else {
			try {
				parsedSize = Integer.parseInt(size);
			}
			catch (NumberFormatException e) {
				throw new InvalidEventQueryException(sizeBoundMessage());
			}
			if (parsedSize < MIN_SIZE || parsedSize > MAX_SIZE) {
				throw new InvalidEventQueryException(sizeBoundMessage());
			}
		}
		OffsetDateTime from = parseBound(occurredFrom, "occurredFrom");
		OffsetDateTime to = parseBound(occurredTo, "occurredTo");
		if (from != null && to != null && from.toInstant().isAfter(to.toInstant())) {
			throw new InvalidEventQueryException("occurredFrom must not be after occurredTo");
		}
		return new EventListQuery(userId, eventType, source, from, to,
				cursor == null ? null : EventCursor.decode(cursor), parsedSize);
	}

	private static OffsetDateTime parseBound(String raw, String parameter) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(raw);
		}
		catch (DateTimeParseException e) {
			throw new InvalidEventQueryException(parameter
					+ " must be an ISO-8601 date-time with an offset, e.g. 2026-10-18T12:00:00Z");
		}
	}

	private static String sizeBoundMessage() {
		return "size must be an integer between " + MIN_SIZE + " and " + MAX_SIZE;
	}
}
