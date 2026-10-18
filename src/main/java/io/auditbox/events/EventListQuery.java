package io.auditbox.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parsed and validated query parameters of GET /api/v1/events (plan task T4.1, decisions
 * D2/D3). The controller receives every parameter as a raw string and this factory applies
 * the read contract:
 *
 * <ul>
 *   <li>{@code userId}, {@code eventType}, {@code source} — optional exact-match filters;
 *       a blank value is treated as an absent filter (review round 3, F6) and any value
 *       containing a NUL character is rejected (F4),</li>
 *   <li>{@code occurredFrom}/{@code occurredTo} — optional inclusive bounds, parsed with the
 *       same strict ISO-8601-with-offset rule as the write-side {@code occurredAt}
 *       ({@link OffsetDateTime#parse}, D10): a value without an offset is invalid, and a
 *       value outside the supported range below is rejected (review round 3, F4),</li>
 *   <li>{@code cursor} — decoded by {@link EventCursor}; a blank value stays 400 (the
 *       cursor is a strict echo of an emitted token, unlike the filters),</li>
 *   <li>{@code size} — page size, default 20, bounded to 1..100; an absent or empty value
 *       falls back to the default.</li>
 * </ul>
 *
 * <p>The supported range of every date-time on the read path (bounds inclusive) is
 * {@link EventTimeRange#TEXT}: far below and above any real event clock, it bounds decode work
 * and is the same range the write path enforces, so a stored event is always a position the
 * cursor can encode and decode (review round 3b, F-1). Values outside it (e.g.
 * {@code +999999999-...}) are rejected by name instead of reaching a comparison or SQL error.
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

	/** Inclusive lower bound of the supported occurrence range (review round 3, F4). */
	static final Instant MIN_SUPPORTED_INSTANT = EventTimeRange.MIN;

	/** Inclusive upper bound of the supported occurrence range (review round 3, F4). */
	static final Instant MAX_SUPPORTED_INSTANT = EventTimeRange.MAX;

	/** The supported range rendered for API messages and documentation. */
	static final String SUPPORTED_RANGE_TEXT = EventTimeRange.TEXT;

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
		return new EventListQuery(parseFilter(userId, "userId"), parseFilter(eventType, "eventType"),
				parseFilter(source, "source"), from, to,
				cursor == null ? null : EventCursor.decode(cursor), parsedSize);
	}

	/**
	 * Normalizes one exact-match filter: a blank value is treated as not filtering at all
	 * (review round 3, F6) — consistent with {@code size} falling back to the default —
	 * instead of matching only the (impossible) empty-string values. A NUL character is
	 * rejected with the parameter named (F4): no stored column value can contain it, so
	 * applying such a filter would silently match nothing in a way the client cannot
	 * distinguish from an empty result engineered by the character itself.
	 */
	private static String parseFilter(String raw, String parameter) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		if (raw.indexOf('\u0000') >= 0) {
			throw new InvalidEventQueryException(parameter + " must not contain NUL characters");
		}
		return raw;
	}

	/**
	 * Parses one inclusive date-time bound with the criteria above: absent/blank means
	 * unset, the strict ISO-8601-with-offset parse (D10) applies, and an instant outside
	 * the supported range is rejected with the parameter named (review round 3, F4).
	 */
	private static OffsetDateTime parseBound(String raw, String parameter) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		OffsetDateTime bound;
		try {
			bound = OffsetDateTime.parse(raw);
		}
		catch (DateTimeParseException e) {
			throw new InvalidEventQueryException(parameter
					+ " must be an ISO-8601 date-time with an offset, e.g. 2026-10-18T12:00:00Z");
		}
		Instant instant = bound.toInstant();
		if (instant.isBefore(MIN_SUPPORTED_INSTANT) || instant.isAfter(MAX_SUPPORTED_INSTANT)) {
			throw new InvalidEventQueryException(parameter + " is outside the supported range "
					+ "(" + SUPPORTED_RANGE_TEXT + ")");
		}
		return bound;
	}

	private static String sizeBoundMessage() {
		return "size must be an integer between " + MIN_SIZE + " and " + MAX_SIZE;
	}
}
