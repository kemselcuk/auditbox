package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Plain unit tests for the metadata number-bound validator (review round 2, F1/F2; re-review
 * blockers B1/B2): no Spring context, no containers. Boundaries are asserted from the digit
 * rules — canonical (trailing zeros stripped, exactly as the content hash canonicalizes, D10)
 * and stored (the instance as parsed, with the literal's scale kept) — and the
 * excessive-exponent cases are asserted to reject without any large-number expansion.
 */
class ValidMetadataValidatorTest {

	private static final ValidMetadataValidator VALIDATOR = new ValidMetadataValidator();

	/**
	 * Reads JSON exactly like the production request mapper (D10): floats as exact
	 * BigDecimal, and the number-length constraint aligned with the validation bounds.
	 */
	private static final JsonMapper JSON = JsonMapper.builder(jsonFactory())
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();

	private static tools.jackson.core.json.JsonFactory jsonFactory() {
		return tools.jackson.core.json.JsonFactory.builder()
				.streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
						.maxNumberLength(JsonFormatMapperConfiguration.MAX_NUMBER_LENGTH)
						.build())
				.build();
	}

	private static boolean valid(JsonNode metadata) {
		return VALIDATOR.isValid(metadata, null);
	}

	private static boolean valid(String metadataJson) {
		return valid(JSON.readTree(metadataJson));
	}

	private static String digits(int count) {
		return "9".repeat(count);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { //
			// trivial shapes: scalars, arrays, nested objects, negative values
			"null", "123", "1.5", "-0.25", "0", "1e3", "[1,2.5,{\"n\":-3}]", "{\"a\":{\"b\":[1e-7]}}",
			// both boundaries: 1000 canonical integer digits, 1000 canonical fractional digits
			"1e999", "0.001e997", "1e-1000", "1.0e-1000",
			// equivalent spellings of legal values (trailing zeros stripped before counting)
			"100.0", "10.00e2", "1", "0.000" })
	void acceptsNumbersWithinTheCanonicalDigitBounds(String metadataJson) {
		assertThat(valid(metadataJson)).isTrue();
	}

	@Test
	void acceptsExactlyOneThousandIntegerDigits() {
		assertThat(valid("{\"n\":" + digits(1000) + "}")).isTrue();
	}

	@Test
	void acceptsExactlyOneThousandFractionalDigits() {
		assertThat(valid("{\"n\":0." + digits(1000) + "}")).isTrue();
	}

	@Test
	void acceptsAtMostOneThousandIntegerAndOneThousandFractionalDigitsAtOnce() {
		// 2000 canonical digits in total: the widest shape validation accepts.
		assertThat(valid("{\"n\":" + digits(1000) + "." + digits(1000) + "}")).isTrue();
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "1e1000", "1.5e1000", "1e-1001", "0.001e-1000", "9.9999e1000", "[1e2000000000]",
			"{\"a\":{\"b\":1e2000000000}}" })
	void rejectsNumbersBeyondTheBounds(String metadataJson) {
		assertThat(valid(metadataJson)).isFalse();
	}

	@Test
	void rejectsAPlainIntegerWithOneDigitTooMany() {
		// Built programmatically: this literal itself exceeds the parser constraint, and the
		// validator must still reject the node in isolation.
		JsonNode node = JsonNodeFactory.instance.objectNode()
				.set("n", JsonNodeFactory.instance.numberNode(new BigDecimal(digits(1001))));
		assertThat(valid(node)).isFalse();
	}

	@Test
	void rejectsAHugePositiveExponentWithoutAnyLargeAllocation() {
		// The 12-byte literal 1e2000000000 rejects in constant time: precision/scale
		// arithmetic only — in particular never toPlainString() on the unbounded value.
		JsonNode node = JSON.readTree("{\"n\":1e2000000000}");

		assertThat(valid(node)).isFalse();
	}

	@Test
	void rejectsAHugeNegativeExponentWithoutAnyLargeAllocation() {
		// The fractional twin: scale 2000000000 exceeds the fraction bound alone.
		assertThat(valid("{\"n\":1e-2000000000}")).isFalse();
	}

	@Test
	void rejectsAnExponentThatOverflowsThirtyTwoBitDigitArithmetic() {
		// B2: 1e2147483647 has precision 1 and scale -2147483647. In 32-bit arithmetic
		// precision - scale wraps to Integer.MIN_VALUE and passed every bound; the hash then
		// called toPlainString() on the unbounded value (OOM). Long arithmetic computes
		// 2147483648 integer digits and rejects in constant time.
		assertThat(valid("{\"n\":1e2147483647}")).isFalse();
	}

	@Test
	void rejectsAnIntOverflowingExponentBuiltProgrammatically() {
		// Same overflow through a hand-built BigDecimal, independent of the JSON parser's
		// exponent handling: unscaled 1, scale Integer.MIN_VALUE + 1.
		JsonNode node = JsonNodeFactory.instance.objectNode().set("n", JsonNodeFactory.instance
				.numberNode(new BigDecimal(BigInteger.ONE, -2147483647)));
		assertThat(valid(node)).isFalse();
	}

	@Test
	void acceptsAStoredFormOfExactlyFourThousandDigits() {
		// B1 boundary. For the literal "1." + "0".repeat(n) + "e-999" the parsed instance has
		// precision n + 1 and scale n + 999, so the stored plain form has
		// max(precision - scale, 0) + max(scale, 0) = n + 999 digits, while the canonical
		// form is always 1e-999 (999 fractional digits, inside the canonical bounds).
		// n = 3001: exactly 4000 stored digits — at the stored-form bound, accepted. The
		// rendering is 4002 characters ("0." plus the digits), under the 4096 read limit.
		assertThat(valid("{\"n\":1." + "0".repeat(3001) + "e-999}")).isTrue();
	}

	@Test
	void rejectsAStoredFormOfFourThousandAndOneDigitsDespiteASmallCanonicalForm() {
		// n = 3002: 4001 stored digits — beyond the stored-form bound while the canonical
		// form (999 fractional digits) is still inside the canonical bounds: only the
		// stored-form check rejects this literal.
		assertThat(valid("{\"n\":1." + "0".repeat(3002) + "e-999}")).isFalse();
	}

	@Test
	void rejectsAStoredFormAboveTheReadLimitThatTheCanonicalBoundsAccept() {
		// The exact shape from re-review B1: n = 3098 renders to 4099 plain characters
		// (4097 stored digits plus "0.") — above the 4096-character read limit, so before
		// the stored-form bound existed it passed the canonical bounds and failed the jsonb
		// insert itself with a 500. The literal itself is under 4096 characters and parses.
		assertThat(valid("{\"n\":1." + "0".repeat(3098) + "e-999}")).isFalse();
	}

	@Test
	void stringValuesAreNeverNumberChecked() {
		// A digit sequence inside a string is string content, stored and hashed as a string:
		// the bound must not reject it.
		assertThat(valid("{\"n\":\"1e2000000000\",\"s\":\"" + digits(2000) + "\"}")).isTrue();
		assertThat(valid("\"1e2000000000\"")).isTrue();
	}

	@Test
	void boundsApplyToTheCanonicalFormNotTheLiteral() {
		// 0.000999...9 (1002 fractional digits) canonicalizes with a 999-nines fraction that
		// still exceeds the bound: the literal spelling cannot smuggle the value through.
		assertThat(valid("{\"n\":0.000" + digits(999) + "}")).isFalse();
	}

	@Test
	void constraintIsWiredAsAFieldErrorNamedMetadata() {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Set<ConstraintViolation<CreateEventRequest>> violations = factory.getValidator().validate(
					new CreateEventRequest("event-id", "user-1", "payment.settled", "core-banking",
							"2026-10-18T12:00:00Z", JSON.readTree("{\"amount\":1e2000000000}")));

			assertThat(violations).hasSize(1);
			assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("metadata");
		}
	}
}
