package io.auditbox.events;

import java.math.BigDecimal;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import tools.jackson.databind.JsonNode;

/**
 * Walks the metadata JSON tree and rejects any number that
 * <ul>
 *   <li>exceeds {@value #MAX_INTEGER_DIGITS} integer digits or {@value #MAX_FRACTIONAL_DIGITS}
 *       fractional digits in its <em>canonical</em> decimal form (trailing zeros stripped, the
 *       same rule {@link EventContentHasher} applies, D10), or</li>
 *   <li>exceeds {@value #MAX_STORED_DIGITS} total plain digits (integer + fractional) in its
 *       <em>stored</em> form — the exact instance as parsed, with the literal's scale kept
 *       (review round 2 re-review, B1).</li>
 * </ul>
 *
 * <h2>Bound logic</h2>
 *
 * <p>For a {@link BigDecimal} {@code v}, digits of the plain decimal form can be computed from
 * {@code precision()} and {@code scale()} alone, without materializing the string: {@code v} is
 * {@code unscaledValue × 10^(-scale)}, so a scale {@code < 0} means the plain form gains
 * characters beyond the unscaled digits ({@code 1e999} has unscaled 1, scale -999 &rarr; 1000
 * integer digits) and a scale {@code > 0} means exactly {@code scale} fractional digits
 * ({@code 1e-1000} has scale 1000 &rarr; 1000 fractional digits).
 *
 * <p>All digit arithmetic is done in {@code long}: in 32-bit arithmetic,
 * {@code 1e2147483647} (precision 1, scale -2147483647) wraps {@code precision - scale} to a
 * negative number that would pass every bound, and the hash would then try to materialize a
 * 2147483648-digit plain string (B2). In {@code long} arithmetic a huge exponent rejects in
 * constant time — no {@code toPlainString()} is ever called on an unbounded value, so no
 * large allocation can happen before rejection.
 *
 * <p>Why the stored instance needs its own bound (B1): parsing keeps the literal's scale, so
 * trailing zeros survive — {@code 1.000…0e-999} canonicalizes to 999 fractional digits
 * (accepted by the canonical bounds) yet its stored plain form has thousands of digits.
 * Hibernate deep-copies the {@code JsonNode} through the {@code FormatMapper} on save
 * (serialize + re-parse), so a stored form above the 4096-digit read constraint of
 * {@link JsonFormatMapperConfiguration} fails the insert itself with a 500 instead of a 400.
 * Bounding the instance's total plain digits keeps every accepted value under that read limit.
 *
 * <p>The exact decimal is extracted with {@link EventContentHasher#canonicalNumber}, so the
 * validator judges exactly the values the content hash canonicalizes — validation and hash
 * always agree (D10).
 */
public class ValidMetadataValidator implements ConstraintValidator<ValidMetadata, JsonNode> {

	/** Maximum integer digits of a metadata number in canonical (trailing-zeros-stripped) decimal form. */
	public static final int MAX_INTEGER_DIGITS = 1000;

	/** Maximum fractional digits of a metadata number in canonical (trailing-zeros-stripped) decimal form. */
	public static final int MAX_FRACTIONAL_DIGITS = 1000;

	/**
	 * Maximum total plain decimal digits (integer + fractional) of a metadata number in its
	 * stored form — the instance as parsed, unstripped (B1). 4000 digits render as at most
	 * 4003 characters (negative sign plus the {@code 0.} prefix), safely under the 4096-digit
	 * read constraint the value must survive on every Hibernate round trip.
	 */
	public static final int MAX_STORED_DIGITS = 4000;

	@Override
	public boolean isValid(JsonNode metadata, ConstraintValidatorContext context) {
		// Presence/null is not judged here: absent and explicit null are the same content (D10).
		return metadata == null || metadata.isNull() || withinBounds(metadata);
	}

	private boolean withinBounds(JsonNode node) {
		if (node.isNumber()) {
			return numberWithinBounds(node);
		}
		if (node.isObject()) {
			for (var property : node.properties()) {
				if (!withinBounds(property.getValue())) {
					return false;
				}
			}
			return true;
		}
		if (node.isArray()) {
			for (JsonNode item : node) {
				if (!withinBounds(item)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Bounds a single number from its canonical and its stored {@code precision()}/{@code
	 * scale()} in constant time and memory: the exponent form {@code 1e2000000000} never
	 * materializes its 2000-million-digit plain rendering (F1/F2, B1/B2).
	 */
	private boolean numberWithinBounds(JsonNode node) {
		BigDecimal raw = EventContentHasher.canonicalNumber(node);
		return canonicalFormWithinBounds(raw) && storedFormWithinBounds(raw);
	}

	/**
	 * Bounds the canonical (trailing-zeros-stripped) form — the form the content hash
	 * canonicalizes to (D10): at most {@link #MAX_INTEGER_DIGITS} integer and
	 * {@link #MAX_FRACTIONAL_DIGITS} fractional digits.
	 */
	private boolean canonicalFormWithinBounds(BigDecimal value) {
		// Reject on the unstripped integer digits first: stripping trailing zeros preserves the
		// integer digit count, and this early return keeps stripTrailingZeros() away from scales
		// near Integer.MIN_VALUE, where it throws ArithmeticException("Overflow") for literals
		// such as 100e2147483647 (re-review round 2c F1, the residual of B2).
		if (integerDigits(value.precision(), value.scale()) > MAX_INTEGER_DIGITS) {
			return false;
		}
		BigDecimal canonical = value.stripTrailingZeros();
		return Math.max(canonical.scale(), 0) <= MAX_FRACTIONAL_DIGITS;
	}

	/**
	 * Bounds the stored form — the exact instance as parsed, with the literal's scale kept
	 * (B1): at most {@link #MAX_STORED_DIGITS} total plain digits, so every accepted value
	 * renders below the 4096-digit read constraint on every Hibernate round trip.
	 */
	private boolean storedFormWithinBounds(BigDecimal value) {
		long scale = value.scale();
		return integerDigits(value.precision(), scale) + Math.max(scale, 0) <= MAX_STORED_DIGITS;
	}

	/**
	 * Integer digits of the plain decimal form of an (unstripped) {@code precision}/{@code
	 * scale} pair, in {@code long} arithmetic (B2): a scale {@code < 0} pads the unscaled
	 * digits with zeros ({@code 1e999} &rarr; 1000 integer digits); a value {@code < 1}
	 * contributes none.
	 */
	private static long integerDigits(long precision, long scale) {
		return scale < 0 ? precision - scale : Math.max(precision - scale, 0);
	}
}
