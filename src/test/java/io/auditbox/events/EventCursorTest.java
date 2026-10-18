package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of the opaque cursor codec of GET /api/v1/events (plan task T4.1): the round
 * trip over the keyset position, the exact encoding contract (URL-safe Base64 without
 * padding over a JSON document) and the strict rejection of every malformed shape — each of
 * which the API must answer with 400 via {@link InvalidEventQueryException}. No Spring
 * context: this is pure encoding logic.
 */
class EventCursorTest {

	private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-18T12:00:00.123456Z");

	@Test
	void roundTripsTheKeysetPosition() {
		String encoded = EventCursor.encode(TIME, "event-1");

		EventCursor.Position position = EventCursor.decode(encoded);

		assertThat(position.occurredAt().toInstant()).isEqualTo(Instant.parse("2026-10-18T12:00:00.123456Z"));
		assertThat(position.eventId()).isEqualTo("event-1");
	}

	@Test
	void encodingIsBase64UrlWithoutPaddingOverAJsonDocument() {
		String encoded = EventCursor.encode(TIME, "event-1");

		// URL-safe alphabet, no padding.
		assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/");
		// The payload is the JSON document over the truncated-to-microsecond UTC occurredAt
		// and the eventId (D3/T4.1).
		String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
		assertThat(json).isEqualTo("{\"occurredAt\":\"2026-10-18T12:00:00.123456Z\",\"eventId\":\"event-1\"}");
	}

	@Test
	void roundTripsEventIdsWithSpecialCharacters() {
		String encoded = EventCursor.encode(TIME, "id with \"quotes\", \\backslashes\nand\ttabs");

		EventCursor.Position position = EventCursor.decode(encoded);

		assertThat(position.eventId()).isEqualTo("id with \"quotes\", \\backslashes\nand\ttabs");
	}

	@Test
	void roundTripsAMaximumLengthControlCharacterEventId() {
		// A legal write-side eventId at the write-side bound (128, D12) that is nothing but
		// control characters is the worst case for the cursor size (review round 3, F1).
		String eventId = "ctl-" + "\u0001".repeat(124);
		assertThat(eventId).hasSize(128);

		String encoded = EventCursor.encode(TIME, eventId);

		EventCursor.Position position = EventCursor.decode(encoded);

		assertThat(position.eventId()).isEqualTo(eventId);
		// 30 bytes of fixed JSON overhead + maximum microsecond UTC occurredAt (~27-30 chars)
		// + 768 escaped characters: ~825-828 JSON bytes -> 1100-1104 base64url characters —
		// within the MAX_CURSOR_LENGTH of 1200, so the API never emits a cursor it refuses.
		assertThat(encoded.length()).isLessThanOrEqualTo(1200);
	}

	@Test
	void subMicrosecondPrecisionIsTruncatedOnEncode() {
		String encoded = EventCursor.encode(TIME.withNano(123_456_789), "event-1");

		EventCursor.Position position = EventCursor.decode(encoded);

		assertThat(position.occurredAt().toInstant()).isEqualTo(Instant.parse("2026-10-18T12:00:00.123456Z"));
	}

	@Test
	void emptyCursorIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode("")).isInstanceOf(InvalidEventQueryException.class);
		assertThatThrownBy(() -> EventCursor.decode("  ")).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void paddedCursorIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode("e30=")).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void nonBase64CharactersAreRejected() {
		assertThatThrownBy(() -> EventCursor.decode("!!!not-base64!!!"))
				.isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void standardBase64AlphabetIsRejected() {
		// "+" and "/" belong to the standard, not the URL-safe alphabet.
		assertThatThrownBy(() -> EventCursor.decode("a+b/cw")).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void decodesToNonJsonDocumentIsRejected() {
		// "aGVsbG8" is valid base64url for "hello" — but not the expected JSON document.
		assertThatThrownBy(() -> EventCursor.decode("aGVsbG8")).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void jsonWithUnexpectedFieldsIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"foo\":\"bar\"}")))
				.isInstanceOf(InvalidEventQueryException.class);
		assertThatThrownBy(() -> EventCursor.decode(
				encodedJson("{\"occurredAt\":\"2026-10-18T12:00:00Z\",\"eventId\":\"e\",\"extra\":1}")))
				.isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void jsonWithUnparseableOccurredAtIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"occurredAt\":\"yesterday\",\"eventId\":\"e\"}")))
				.isInstanceOf(InvalidEventQueryException.class);
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"occurredAt\":\"2026-10-18\",\"eventId\":\"e\"}")))
				.isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void jsonWithNonTextualFieldsIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"occurredAt\":1,\"eventId\":\"e\"}")))
				.isInstanceOf(InvalidEventQueryException.class);
		assertThatThrownBy(() -> EventCursor.decode(
				encodedJson("{\"occurredAt\":\"2026-10-18T12:00:00Z\",\"eventId\":42}")))
				.isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void blankOrOversizedEventIdIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"occurredAt\":\"2026-10-18T12:00:00Z\","
				+ "\"eventId\":\" \"}"))).isInstanceOf(InvalidEventQueryException.class);
		assertThatThrownBy(() -> EventCursor.decode(encodedJson("{\"occurredAt\":\"2026-10-18T12:00:00Z\","
				+ "\"eventId\":\"" + "e".repeat(129) + "\"}"))).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void cursorJsonWithDuplicateKeysIsRejected() {
		// Strict duplicate-key detection (review round 3, F3): without the feature the mapper
		// would silently let the second occurredAt win and decode the token non-deterministically.
		assertThatThrownBy(() -> EventCursor.decode(encodedJson(
				"{\"occurredAt\":\"2026-10-18T12:00:00Z\",\"occurredAt\":\"2026-10-18T12:00:01Z\","
						+ "\"eventId\":\"e\"}"))).isInstanceOf(InvalidEventQueryException.class);
	}

	@Test
	void cursorOccurredAtOutsideTheSupportedRangeIsRejected() {
		// Range bounds (review round 3, F4), inclusive on both ends, same as the query bounds.
		assertThat(EventCursor.decode(encodedJson(
				"{\"occurredAt\":\"0001-01-01T00:00:00Z\",\"eventId\":\"e\"}")).occurredAt().toInstant())
				.isEqualTo(Instant.parse("0001-01-01T00:00:00Z"));
		assertThat(EventCursor.decode(encodedJson(
				"{\"occurredAt\":\"9999-12-31T23:59:59.999999Z\",\"eventId\":\"e\"}")).occurredAt().toInstant())
				.isEqualTo(Instant.parse("9999-12-31T23:59:59.999999Z"));
		// Below the range: year 0 is a parseable OffsetDateTime...
		assertThatThrownBy(() -> EventCursor.decode(
				encodedJson("{\"occurredAt\":\"0000-01-01T00:00:00Z\",\"eventId\":\"e\"}")))
				.isInstanceOf(InvalidEventQueryException.class)
				.hasMessageContaining("occurredAt");
		// ...and above it (the crafted far-future position must not pass validation):
		assertThatThrownBy(() -> EventCursor.decode(
				encodedJson("{\"occurredAt\":\"+294277-01-01T00:00:00Z\",\"eventId\":\"e\"}")))
				.isInstanceOf(InvalidEventQueryException.class)
				.hasMessageContaining("occurredAt");
	}

	@Test
	void cursorEventIdWithNulCharacterIsRejected() {
		// The JSON escape of U+0000 is six literal characters inside the base64 token (review
		// round 3, F4) — such an eventId could never match a stored row.
		assertThatThrownBy(() -> EventCursor.decode(
				encodedJson("{\"occurredAt\":\"2026-10-18T12:00:00Z\",\"eventId\":\"e\\u0000x\"}")))
				.isInstanceOf(InvalidEventQueryException.class)
				.hasMessageContaining("eventId");
	}

	@Test
	void oversizedCursorIsRejected() {
		// Just over the raised MAX_CURSOR_LENGTH of 1200 (review round 3, F1): the pre-parse
		// bound still rejects oversized garbage before any decoding.
		assertThatThrownBy(() -> EventCursor.decode("e".repeat(1201)))
				.isInstanceOf(InvalidEventQueryException.class)
				.hasMessageContaining("too long");
	}

	private static String encodedJson(String json) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
