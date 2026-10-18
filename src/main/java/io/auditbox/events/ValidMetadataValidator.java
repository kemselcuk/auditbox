package io.auditbox.events;

import java.math.BigDecimal;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import tools.jackson.databind.JsonNode;

/**
 * Walks the metadata JSON tree and rejects any number whose canonical decimal form
 * (trailing zeros stripped, the same rule {@link EventContentHasher} applies, D10) exceeds
 * {@value #MAX_INTEGER_DIGITS} integer digits or {@value #MAX_FRACTIONAL_DIGITS} fractional
 * digits (review round 2, F1/F2).
 *
 * <h2>Bound logic</h2>
 *
 * <p>For a {@link BigDecimal} {@code v}, digits of the canonical plain decimal form can be
 * computed from {@code precision()} and {@code scale()} alone, without materializing the
 * string: {@code v} is {@code unscaledValue × 10^(-scale)}, so a scale {@code < 0} means the
 * canonical plain form gains characters beyond the unscaled digits ({@code 1e999} has
 * unscaled 1, scale -999 → 1000 integer digits) and a scale {@code > 0} means exactly
 * {@code scale} fractional digits ({@code 1e-1000} has scale 1000 → 1000 fractional digits).
 * A huge exponent therefore rejects in constant time — {@code 1e2000000000} has precision 1,
 * scale -2000000000, i.e. 2000000001 integer digits — and no {@code toPlainString()} is ever
 * called on an unbounded value, so no large allocation can happen before rejection.
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
	 * Bounds a single number from its canonical {@code precision()}/{@code scale()} in
	 * constant time and memory: the exponent form {@code 1e2000000000} never materializes
	 * its 2000-million-digit plain rendering (F1/F2).
	 */
	private boolean numberWithinBounds(JsonNode node) {
		BigDecimal value = EventContentHasher.canonicalNumber(node).stripTrailingZeros();
		int scale = value.scale();
		int integerDigits = scale < 0
				// scale < 0: the plain form is the unscaled digits padded with -scale zeros
				// (1e999 = unscaled 1, scale -999 = 1000 integer digits).
				? value.precision() - scale
				// A canonical value < 1 ("0.5", "1e-1000") contributes no integer digits.
				: Math.max(value.precision() - scale, 0);
		return integerDigits <= MAX_INTEGER_DIGITS && Math.max(scale, 0) <= MAX_FRACTIONAL_DIGITS;
	}
}
