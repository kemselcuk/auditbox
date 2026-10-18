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
	void oversizedCursorIsRejected() {
		assertThatThrownBy(() -> EventCursor.decode("e".repeat(513)))
				.isInstanceOf(InvalidEventQueryException.class);
	}

	private static String encodedJson(String json) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
