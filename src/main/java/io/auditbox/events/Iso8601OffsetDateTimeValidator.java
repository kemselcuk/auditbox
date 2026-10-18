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
 * value accepted here can never fail inside the hash computation. The parsed instant must also
 * fall inside the shared supported range ({@link EventTimeRange}, D1/D13), so every stored
 * {@code occurredAt} is a cursor position the read path accepts (review round 3b, F-1).
 */
public class Iso8601OffsetDateTimeValidator implements ConstraintValidator<Iso8601OffsetDateTime, String> {

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			// Presence is @NotNull's job; this constraint only judges the format.
			return true;
		}
		try {
			OffsetDateTime parsed = OffsetDateTime.parse(value);
			// The write path enforces the same supported range the read path validates
			// (review round 3b, F-1): a stored occurredAt is always a cursor position the
			// read side accepts.
			return EventTimeRange.supports(parsed.toInstant());
		}
		catch (DateTimeParseException e) {
			return false;
		}
	}
}
