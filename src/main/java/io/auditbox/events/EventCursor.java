package io.auditbox.events;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque continuation cursor of GET /api/v1/events (plan task T4.1, decision D3).
 *
 * <p>The cursor content is the keyset position of the last item of the previous page: its
 * {@code occurredAt} (stored in UTC at microsecond precision, D10/D12) and its
 * {@code eventId}. Both are serialized as a small JSON document and encoded with URL-safe
 * Base64 without padding. JSON (via the Jackson 3 mapper already on the classpath) keeps
 * eventIds containing quotes or control characters safe; {@code Base64.getUrlEncoder()
 * .withoutPadding()} yields exactly the URL-safe, padding-free alphabet the contract
 * requires.
 *
 * <p>The document shape is deliberately strict on decode — exactly the two fields, a
 * parseable date-time, a non-blank eventId — because the cursor is an opaque token clients
 * must echo verbatim: anything else is malformed content and surfaces as 400 via
 * {@link InvalidEventQueryException} (T4.1).
 */
final class EventCursor {

	/**
	 * A decoded keyset position: everything strictly before it, under the
	 * (occurredAt DESC, eventId DESC) order, is covered by the previous pages.
	 */
	record Position(OffsetDateTime occurredAt, String eventId) {
	}

	/**
	 * Cursor length bound. A genuine cursor is about 120 characters; the generous cap keeps
	 * the decode cost bounded (the write-side eventId bound is 128 characters, D12) and
	 * rejects oversized garbage before any parsing.
	 */
	private static final int MAX_CURSOR_LENGTH = 512;

	/** The event id length bound of the write side (D12) — a cursor position cannot exceed it. */
	private static final int MAX_EVENT_ID_LENGTH = 128;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private EventCursor() {
	}

	/**
	 * Encodes the keyset position of a page's last item. {@code occurredAt} is truncated to
	 * microseconds and normalized to UTC first — the same normalization the write path
	 * applies (D10/D12) — so the encoded form always matches the stored value.
	 */
	static String encode(OffsetDateTime occurredAt, String eventId) {
		OffsetDateTime position = occurredAt.truncatedTo(ChronoUnit.MICROS).withOffsetSameInstant(ZoneOffset.UTC);
		JsonNode node = JSON.createObjectNode().put("occurredAt", position.toString()).put("eventId", eventId);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(node));
	}

	/**
	 * Decodes a client-supplied cursor into its keyset position. Any deviation from the
	 * encoded shape — characters outside the URL-safe alphabet, padding, non-JSON content,
	 * unexpected fields, an unparseable {@code occurredAt}, a blank or oversized
	 * {@code eventId} — raises {@link InvalidEventQueryException} (400, T4.1).
	 */
	static Position decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			throw new InvalidEventQueryException("cursor is malformed (the value is empty)");
		}
		if (cursor.length() > MAX_CURSOR_LENGTH) {
			throw new InvalidEventQueryException("cursor is malformed (the value is too long)");
		}
		if (cursor.indexOf('=') >= 0) {
			throw new InvalidEventQueryException("cursor is malformed (padding is not allowed)");
		}
		byte[] decoded;
		try {
			decoded = Base64.getUrlDecoder().decode(cursor);
		}
		catch (IllegalArgumentException e) {
			throw new InvalidEventQueryException("cursor is malformed (not base64url without padding)");
		}
		JsonNode node;
		try {
			node = JSON.readTree(decoded);
		}
		catch (JacksonException e) {
			throw new InvalidEventQueryException("cursor is malformed (not the expected JSON document)");
		}
		if (node == null || !node.isObject() || node.size() != 2
				|| !node.has("occurredAt") || !node.has("eventId")
				|| !node.get("occurredAt").isTextual() || !node.get("eventId").isTextual()) {
			throw new InvalidEventQueryException("cursor is malformed (expected the occurredAt and eventId fields)");
		}
		OffsetDateTime occurredAt;
		try {
			occurredAt = OffsetDateTime.parse(node.get("occurredAt").asString())
					// Defensive: stored positions are microsecond-truncated (D10/D12); a crafted
					// cursor must not smuggle in a finer precision than the stored rows have.
					.truncatedTo(ChronoUnit.MICROS);
		}
		catch (DateTimeParseException e) {
			throw new InvalidEventQueryException("cursor is malformed (occurredAt is not an ISO-8601 date-time)");
		}
		String eventId = node.get("eventId").asString();
		if (eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
			throw new InvalidEventQueryException("cursor is malformed (eventId must be 1..128 characters)");
		}
		return new Position(occurredAt, eventId);
	}
}
