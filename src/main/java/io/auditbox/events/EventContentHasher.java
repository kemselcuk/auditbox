package io.auditbox.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Deterministic SHA-256 content hash over the canonical JSON of an event's content fields
 * (plan task T2.2). Decision D4 compares these hashes to decide whether a repeated
 * {@code eventId} is an idempotent retry (identical content) or a conflict (different content).
 *
 * <h2>Hash rule</h2>
 *
 * <p>The hash is the lowercase hexadecimal SHA-256 of the UTF-8 bytes of a canonical JSON
 * string built from exactly these content fields: {@code eventId}, {@code userId},
 * {@code eventType}, {@code source}, {@code occurredAt} (normalized) and {@code metadata}
 * (canonicalized). Server-side fields — {@code id}, {@code receivedAt}, {@code createdAt} —
 * are excluded, so a retry of the same event hashes identically no matter when the server
 * saw it.
 *
 * <h2>Canonicalization rules</h2>
 *
 * <ul>
 *   <li><strong>Key order.</strong> Object keys are sorted at every level (Jackson
 *       {@code ORDER_MAP_ENTRIES_BY_KEYS}); the canonical form is a single JSON object whose
 *       content-field keys also end up in alphabetical order.</li>
 *   <li><strong>occurredAt.</strong> Parsed as an ISO-8601 date-time with offset and
 *       normalized to the equivalent UTC instant via {@link java.time.Instant#toString()},
 *       so {@code 2026-10-18T12:00:00Z}, {@code 2026-10-18T14:00:00+02:00} and
 *       {@code 2026-10-18T12:00:00.000Z} all hash identically. Input must be the strict
 *       ISO-8601 offset form; anything else fails fast with
 *       {@link java.time.format.DateTimeParseException} (Stage 3 validation surfaces it).</li>
 *   <li><strong>metadata null rule.</strong> An absent metadata value and an explicit JSON
 *       {@code null} are the same content: both canonicalize to JSON {@code null}. Any
 *       present value — including an empty object — is different content.</li>
 *   <li><strong>Numbers.</strong> Canonicalized as exact decimals
 *       ({@link BigDecimal#stripTrailingZeros().toPlainString()}), so {@code 1}, {@code 1.0}
 *       and {@code 1.00} hash identically, and {@code 0.1} and {@code 0.10} hash identically.
 *       Known limit: a value that reached the tree as a JSON double is canonicalized through
 *       its shortest round-trip decimal (the {@code Double.toString} representation), so
 *       binary-float artifacts beyond that shortest form are not preserved. Clients that need
 *       exact high-precision decimals should send a plain decimal form and Stage 3 should read
 *       metadata with {@code USE_BIG_DECIMAL_FOR_FLOATS} to keep it exact end to end.</li>
 *   <li><strong>Strings and other scalars.</strong> Strings are written as JSON strings with
 *       standard Jackson escaping; booleans and nulls as JSON literals. The whole input is
 *       treated as UTF-8.</li>
 * </ul>
 *
 * <p>The class is a stateless utility over immutable, shared Jackson objects and is safe for
 * concurrent use. Null content fields fail fast ({@link NullPointerException}); the hash must
 * never be computed for content that skipped Stage 3 validation.
 */
public final class EventContentHasher {

	/**
	 * Jackson writer for the canonical form: keys sorted at every level, numbers written as
	 * fixed plain decimals (no exponent notation, no trailing zeros).
	 */
	private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.addModule(new SimpleModule().addSerializer(BigDecimal.class, new CanonicalNumberSerializer()))
			.build();

	private EventContentHasher() {
	}

	/**
	 * Computes the SHA-256 content hash (lowercase hex, 64 characters) over the canonical
	 * JSON of the given content fields.
	 *
	 * @param eventId    client-generated event identifier (never null)
	 * @param userId     user identifier (never null)
	 * @param eventType  event type (never null)
	 * @param source     event source (never null)
	 * @param occurredAt ISO-8601 date-time with offset, e.g. {@code 2026-10-18T12:00:00Z}
	 *                   or {@code 2026-10-18T14:00:00+02:00} (never null)
	 * @param metadata   optional metadata tree; {@code null} and an explicit JSON null are
	 *                   the same content
	 * @return the lowercase hex SHA-256 of the canonical JSON, 64 characters
	 */
	public static String hash(String eventId, String userId, String eventType, String source, String occurredAt,
			JsonNode metadata) {
		byte[] canonicalJsonBytes = canonicalJson(eventId, userId, eventType, source, occurredAt, metadata)
				.getBytes(StandardCharsets.UTF_8);
		return HexFormat.of().formatHex(sha256().digest(canonicalJsonBytes));
	}

	/**
	 * Builds the canonical JSON string that {@link #hash} digests. Exposed because the
	 * canonical form itself is part of the documented hash rule and is easier to inspect
	 * than the digest.
	 *
	 * @return canonical JSON, e.g. {@code {"eventId":"...","eventType":"...","metadata":null,
	 *         "occurredAt":"2026-10-18T12:00:00Z","source":"...","userId":"..."}}
	 */
	public static String canonicalJson(String eventId, String userId, String eventType, String source,
			String occurredAt, JsonNode metadata) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(eventType, "eventType must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");

		Map<String, Object> content = new LinkedHashMap<>();
		content.put("eventId", eventId);
		content.put("userId", userId);
		content.put("eventType", eventType);
		content.put("source", source);
		content.put("occurredAt", normalizeOccurredAt(occurredAt));
		content.put("metadata", canonicalValue(metadata));
		return CANONICAL_JSON.writeValueAsString(content);
	}

	/**
	 * Normalizes an ISO-8601 offset date-time to the UTC instant string used in the
	 * canonical form. Equal instants always produce equal strings (including trailing-zero
	 * fraction trimming, e.g. {@code 12:00:00.000Z} becomes {@code 12:00:00Z}).
	 */
	private static String normalizeOccurredAt(String occurredAt) {
		return OffsetDateTime.parse(occurredAt).toInstant().toString();
	}

	/**
	 * Maps a metadata tree to plain Java values the canonical writer can serialize
	 * deterministically: objects to maps (key order then handled by the writer), arrays to
	 * lists, numbers to {@link BigDecimal}. Absent and explicit null both map to null.
	 */
	private static Object canonicalValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isObject()) {
			Map<String, Object> object = new LinkedHashMap<>();
			node.properties().forEach(entry -> object.put(entry.getKey(), canonicalValue(entry.getValue())));
			return object;
		}
		if (node.isArray()) {
			List<Object> array = new ArrayList<>();
			node.forEach(item -> array.add(canonicalValue(item)));
			return array;
		}
		if (node.isNumber()) {
			return canonicalNumber(node);
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		// Remaining scalars (strings and exotic node types) keep their text representation.
		return node.stringValue();
	}

	/**
	 * Converts any numeric node to the exact BigDecimal used for canonicalization.
	 * DoubleNode/FloatNode go through {@link BigDecimal#valueOf(double)} (the shortest
	 * decimal that round-trips the value); decimal and integer nodes keep their exact value.
	 */
	private static BigDecimal canonicalNumber(JsonNode node) {
		if (node.isBigDecimal()) {
			return node.decimalValue();
		}
		if (node.isBigInteger()) {
			return new BigDecimal(node.bigIntegerValue());
		}
		if (node.isFloatingPointNumber()) {
			return BigDecimal.valueOf(node.doubleValue());
		}
		return BigDecimal.valueOf(node.longValue());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			// Every JRE must provide SHA-256; this cannot happen on a supported Java 21 runtime.
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}

	/**
	 * Serializes numbers as fixed plain decimals: stripTrailingZeros removes scale
	 * differences ({@code 1.0} and {@code 1.00} become {@code 1}) and toPlainString avoids
	 * exponent notation ({@code 1E+3} becomes {@code 1000}).
	 */
	private static final class CanonicalNumberSerializer extends StdSerializer<BigDecimal> {

		private CanonicalNumberSerializer() {
			super(BigDecimal.class);
		}

		@Override
		public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
			generator.writeNumber(value.stripTrailingZeros().toPlainString());
		}
	}
}
