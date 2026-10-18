package io.auditbox.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque continuation cursor of GET /api/v1/events (plan task T4.1, decision D3).
 *
 * <p>The cursor content is the keyset position of the last item of the previous page: its
 * {@code occurredAt} and its {@code eventId}. Both are serialized as a small JSON document
 * and encoded with URL-safe Base64 without padding. JSON (via the Jackson 3 mapper already
 * on the classpath) keeps eventIds containing quotes or control characters safe;
 * {@code Base64.getUrlEncoder().withoutPadding()} yields exactly the URL-safe, padding-free
 * alphabet the contract requires.
 *
 * <p>The encoded {@code occurredAt} shape is the {@code OffsetDateTime#toString} form of the
 * microsecond-truncated UTC instant (D10/D12): a standard ISO-8601 UTC instant with
 * <em>variable</em> precision — the seconds field is omitted only when the seconds and the
 * fraction are both zero; a non-zero fraction is written with 3 digits when it is whole
 * milliseconds and with 6 digits otherwise, keeping trailing zeros inside that group. It is
 * deliberately not a fixed-width ".SSSSSS" string; decode parses whatever precision the token
 * carries.
 *
 * <p>The document shape is deliberately strict on decode — exactly the two fields, a
 * parseable date-time within the supported range (see {@link EventListQuery}), a non-blank,
 * NUL-free eventId with at most the write-side length — because the cursor is an opaque
 * token clients must echo verbatim: anything else is malformed content and surfaces as 400
 * via {@link InvalidEventQueryException} (T4.1).
 */
final class EventCursor {

	/**
	 * A decoded keyset position: everything strictly before it, under the
	 * (occurredAt DESC, eventId DESC) order, is covered by the previous pages.
	 */
	record Position(OffsetDateTime occurredAt, String eventId) {
	}

	/**
	 * Cursor length bound. The pre-parse bound must exceed the length of any cursor
	 * {@link #encode} can emit. For values the current write contract accepts
	 * ({@code occurredAt} 0001..9999, eventId up to 128 characters, D12): a 128-character
	 * eventId of nothing but control characters JSON-escapes to 128 &times; 6 = 768
	 * characters; the longest supported timestamp is 27 characters
	 * ({@code 9999-12-31T23:59:59.999999Z}); fixed JSON overhead is ~30 bytes
	 * ({@code {"occurredAt":"","eventId":""}}) — 825 JSON bytes, i.e. 1100 base64url
	 * characters. Legacy/out-of-band positions with a 6-digit year (e.g. PostgreSQL's
	 * maximum {@code +294276-...}) reach ~828 bytes, i.e. 1104 characters. 1200 leaves the
	 * generous margin above both while still rejecting oversized garbage before any parsing
	 * (review round 3, F1). Rows written by this API version can therefore never produce an
	 * emitted-then-rejected cursor.
	 */
	private static final int MAX_CURSOR_LENGTH = 1200;

	/** The event id length bound of the write side (D12) — a cursor position cannot exceed it. */
	private static final int MAX_EVENT_ID_LENGTH = 128;

	/**
	 * The cursor JSON mapper. Strict duplicate-key detection (review round 3, F3) makes a
	 * JSON document with two {@code occurredAt} (or {@code eventId}) keys a parse failure
	 * instead of a last-one-wins lookup: the duplicate is ambiguous content and must be
	 * rejected as malformed, not silently narrowed.
	 */
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.build();

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
	 * duplicate JSON keys, unexpected fields, an unparseable or out-of-range
	 * {@code occurredAt}, a blank, oversized or NUL-bearing {@code eventId} — raises
	 * {@link InvalidEventQueryException} (400, T4.1, review round 3 F4).
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
		// Same supported range as the occurredFrom/occurredTo bounds (review round 3, F4):
		// a crafted position outside it could never match a stored row and must not slip
		// past validation.
		Instant instant = occurredAt.toInstant();
		if (!EventTimeRange.supports(instant)) {
			throw new InvalidEventQueryException("cursor is malformed (occurredAt is outside the supported range "
					+ EventTimeRange.TEXT + ")");
		}
		String eventId = node.get("eventId").asString();
		if (eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
			throw new InvalidEventQueryException("cursor is malformed (eventId must be 1..128 characters)");
		}
		// A NUL character can never match a stored event_id (PostgreSQL text rejects it at
		// the column level), so the position is unusable content: reject it here (F4) with
		// a GET-oriented message instead of surfacing a data-exception error from insert.
		if (eventId.indexOf('\u0000') >= 0) {
			throw new InvalidEventQueryException("cursor is malformed (eventId must not contain NUL characters)");
		}
		return new Position(occurredAt, eventId);
	}
}
