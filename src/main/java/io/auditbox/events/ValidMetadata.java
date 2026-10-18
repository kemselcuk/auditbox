package io.auditbox.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Bean Validation constraint for the metadata tree (plan Stage 3, review round 2 F1/F2):
 * rejects any JSON number that exceeds either of two bounds, both counted by
 * {@link ValidMetadataValidator} from the exact {@link BigDecimal} the request mapper read:
 *
 * <ul>
 *   <li>the <em>canonical</em> decimal form (trailing zeros stripped, same rule as the
 *       content hash, D10) is limited to at most {@code 1000} integer and at most
 *       {@code 1000} fractional digits, and</li>
 *   <li>the <em>stored</em> form — the instance as parsed, with the literal's scale kept —
 *       is limited to at most {@code 4000} total plain digits (re-review blocker B1).</li>
 * </ul>
 *
 * <p>Why the bounds exist: a metadata number must survive the full round trip — request
 * parse (D10), hash canonicalization, jsonb storage and the jsonb text read-back — and
 * PostgreSQL {@code jsonb} renders numbers as plain decimals, so a tiny exponent literal
 * such as {@code 1e2000000000} would silently expand to gigabytes of digits on the read
 * path (inserted successfully in 201, then unreadable at 500 on replay), and a literal
 * such as {@code 1.000…0e-999} with thousands of trailing zeros passes the canonical
 * bounds but fails the jsonb insert itself. Bounding both forms at validation keeps
 * 400-for-definitely-unrepresentable content before any such allocation can happen.
 */
@Documented
@Constraint(validatedBy = ValidMetadataValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidMetadata {

	String message() default "metadata must not contain numbers exceeding 1000 integer digits "
			+ "or 1000 fractional digits in canonical decimal form, or 4000 total digits "
			+ "in the stored (unstripped) decimal form";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
