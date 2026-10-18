package io.auditbox.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * Plain unit tests for the metadata number-bound validator (review round 2, F1/F2): no
 * Spring context, no containers. Boundaries are asserted from the canonical digit rule —
 * trailing zeros stripped exactly as the content hash canonicalizes (D10) — and the
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
