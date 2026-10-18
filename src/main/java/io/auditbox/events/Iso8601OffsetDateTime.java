package io.auditbox.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Bean Validation constraint for the strict ISO-8601 date-time form the API contract
 * requires (D10): a date-time with a mandatory UTC offset ({@code Z} or {@code +/-hh:mm}),
 * e.g. {@code 2026-10-18T12:00:00Z} or {@code 2026-10-18T14:00:00+02:00}.
 *
 * <p>Validation is a field-level check on the raw request string so a malformed
 * {@code occurredAt} shows up as a regular field error in the 400 Problem Details response
 * (with the same shape as every other validation failure) instead of a body-level parse
 * error.
 */
@Documented
@Constraint(validatedBy = Iso8601OffsetDateTimeValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Iso8601OffsetDateTime {

	String message() default "occurredAt must be a strict ISO-8601 date-time with a UTC offset (Z or +/-hh:mm) between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59.999999Z, e.g. 2026-10-18T12:00:00Z";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
