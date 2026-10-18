package io.auditbox.events;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates the strict ISO-8601 offset form via {@link OffsetDateTime#parse(CharSequence)}
 * ({@code ISO_OFFSET_DATE_TIME}, strict resolver): requires the offset ({@code Z} or
 * {@code +/-hh:mm}), rejects date-only values, offsets missing from the value, and any
 * other malformed input. This is exactly the parse the canonical hash applies (D10), so a
 * value accepted here can never fail inside the hash computation.
 */
public class Iso8601OffsetDateTimeValidator implements ConstraintValidator<Iso8601OffsetDateTime, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			// Presence is @NotNull's job; this constraint only judges the format.
			return true;
		}
		try {
			OffsetDateTime.parse(value);
			return true;
		}
		catch (DateTimeParseException e) {
			return false;
		}
	}
}
