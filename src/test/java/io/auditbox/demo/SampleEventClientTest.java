package io.auditbox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Light unit tests for the non-network parts of the sample-event client (plan task T6.1):
 * argument parsing over the safe defaults, the deterministic {@code demo-<batch>-<i>} eventId
 * scheme, the hand-built JSON payloads (including minimal escaping) and the per-batch
 * determinism that makes re-running a batch an idempotent replay (D4).
 */
class SampleEventClientTest {

	// ---------------------------------------------------------------------
	// Configuration parsing (T6.1)
	// ---------------------------------------------------------------------

	@Test
	void defaultsAreAppliedWhenNoArgumentsAreGiven() {
		SampleEventClient.Config config = SampleEventClient.Config.parse();

		assertThat(config.baseUrl()).isEqualTo("http://localhost:8080");
		assertThat(config.apiKey()).isEqualTo("local-dev-key");
		assertThat(config.count()).isEqualTo(20);
		// The default batch id is a UTC timestamp: 16 chars yyyyMMddTHHmmssZ.
		assertThat(config.batch()).hasSize(16).matches("\\d{8}T\\d{6}Z");
	}

	@Test
	void allFlagsCanBeOverridden() {
		SampleEventClient.Config config = SampleEventClient.Config.parse(
				"--base-url=http://localhost:9090", "--api-key=other-key", "--count=5", "--batch=demo-run-1");

		assertThat(config.baseUrl()).isEqualTo("http://localhost:9090");
		assertThat(config.apiKey()).isEqualTo("other-key");
		assertThat(config.count()).isEqualTo(5);
		assertThat(config.batch()).isEqualTo("demo-run-1");
	}

	@Test
	void argsCanComeInAnyOrder() {
		SampleEventClient.Config config = SampleEventClient.Config.parse("--batch=b2", "--count=3");

		assertThat(config.count()).isEqualTo(3);
		assertThat(config.batch()).isEqualTo("b2");
		assertThat(config.baseUrl()).isEqualTo("http://localhost:8080");
		assertThat(config.apiKey()).isEqualTo("local-dev-key");
	}

	@Test
	void invalidCountIsRejected() {
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--count=0"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("count must be at least 1");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--count=10001"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("count must not exceed");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--count=ten"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("count must be a positive integer");
	}

	@Test
	void malformedArgumentsAreRejected() {
		assertThatThrownBy(() -> SampleEventClient.Config.parse("count=3"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported argument");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--unknown=1"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown flag --unknown");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--batch="))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("blank value");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--batch=with spaces"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("batch must be 1..100 characters");
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--batch=" + "x".repeat(101)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("batch must be 1..100 characters");
	}

	// ---------------------------------------------------------------------
	// --base-url validation and normalization (round 5, F2)
	// ---------------------------------------------------------------------

	@Test
	void validHttpAndHttpsBaseUrlsAreAccepted() {
		assertThat(SampleEventClient.Config.parse("--base-url=http://10.1.2.3:8080").baseUrl())
				.isEqualTo("http://10.1.2.3:8080");
		assertThat(SampleEventClient.Config.parse("--base-url=https://auditbox.internal").baseUrl())
				.isEqualTo("https://auditbox.internal");
	}

	@Test
	void trailingSlashesAreStrippedSoRequestPathsNeverDoubleTheSlash() {
		assertThat(SampleEventClient.Config.parse("--base-url=http://localhost:8080/").baseUrl())
				.isEqualTo("http://localhost:8080");
		assertThat(SampleEventClient.Config.parse("--base-url=https://demo.example.org//").baseUrl())
				.isEqualTo("https://demo.example.org");
	}

	@Test
	void invalidBaseUrlsAreRejectedWithTheUsageError() {
		// No scheme and no host: parsed as a URI, but not an absolute http/https URL.
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--base-url=localhost:8080"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base-url must be an absolute http/https URL");
		// Unsupported scheme.
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--base-url=ftp://host"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base-url must be an absolute http/https URL");
		// Absolute scheme but no host.
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--base-url=http://"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base-url must be an absolute http/https URL");
		// Not a URI at all (contains a space).
		assertThatThrownBy(() -> SampleEventClient.Config.parse("--base-url=http://ho st:8080"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("base-url must be an absolute http/https URL");
	}

	// ---------------------------------------------------------------------
	// Exit-code mapping (round 5, F4) and failure descriptions (F3)
	// ---------------------------------------------------------------------

	@Test
	void exitCodeIsZeroWhenEveryRequestWasAccepted() {
		assertThat(SampleEventClient.exitCode(Map.of())).isZero();
		assertThat(SampleEventClient.exitCode(Map.of(200, 6))).isZero();
		assertThat(SampleEventClient.exitCode(Map.of(200, 3, 201, 3))).isZero();
	}

	@Test
	void exitCodeIsOneOnConnectionFailureOrStatusAtOrAbove400() {
		assertThat(SampleEventClient.exitCode(Map.of(-1, 5))).isEqualTo(1);
		assertThat(SampleEventClient.exitCode(Map.of(401, 1))).isEqualTo(1);
		assertThat(SampleEventClient.exitCode(Map.of(500, 2))).isEqualTo(1);
		assertThat(SampleEventClient.exitCode(Map.of(200, 4, 401, 1))).isEqualTo(1);
	}

	@Test
	void failureWithoutMessageFallsBackToTheExceptionSimpleName() {
		// A refused java.net TCP connection carries no message: the raw prints showed "null".
		assertThat(SampleEventClient.describeFailure(new java.net.ConnectException()))
				.isEqualTo("ConnectException");
		assertThat(SampleEventClient.describeFailure(new java.net.ConnectException("Connection refused")))
				.isEqualTo("Connection refused");
	}

	// ---------------------------------------------------------------------
	// eventId scheme and JSON building (T6.1)
	// ---------------------------------------------------------------------

	@Test
	void eventIdsFollowTheDemoBatchIndexScheme() {
		assertThat(SampleEventClient.eventId("20261018T120000Z", 0)).isEqualTo("demo-20261018T120000Z-0");
		assertThat(SampleEventClient.eventId("run-1", 12)).isEqualTo("demo-run-1-12");
		// Inside the 128-character API limit for every accepted batch id.
		assertThat(SampleEventClient.eventId("x".repeat(100), 9999)).hasSizeLessThanOrEqualTo(128);
	}

	@Test
	void jsonEscapeHandlesQuotesBackslashesAndControlCharacters() {
		assertThat(SampleEventClient.jsonEscape("plain")).isEqualTo("plain");
		assertThat(SampleEventClient.jsonEscape("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
		assertThat(SampleEventClient.jsonEscape("tab\tnl\n")).isEqualTo("tab\\tnl\\n");
		assertThat(SampleEventClient.jsonEscape("\u0001")).isEqualTo("\\u0001");
		assertThat(SampleEventClient.jsonEscape("unicode-é-中")).isEqualTo("unicode-é-中");
	}

	@Test
	void payloadContainsAllCoreFieldsAndSmallMetadata() {
		String payload = SampleEventClient.payload("run-1", Instant.parse("2026-10-18T12:00:00Z"), 3);

		assertThat(payload).startsWith("{\"eventId\":\"demo-run-1-3\"");
		assertThat(payload).contains("\"userId\":\"demo-user-3\"");
		assertThat(payload).contains("\"eventType\":\"account.updated\""); // 3 % 4
		assertThat(payload).contains("\"source\":\"core-banking\""); // 3 % 3
		assertThat(payload).contains("\"occurredAt\":\"2026-10-18T11:57:00Z\""); // base - 3 minutes
		assertThat(payload).contains("\"metadata\":{\"amount\":13.50,\"currency\":\"EUR\"}}"); // 10 + 3 % 50
		// The hand-built body is valid JSON when parsed — reject it if the builder regresses.
		assertThat(parse(payload)).isNotNull();
	}

	// ---------------------------------------------------------------------
	// Per-batch determinism (the D4 idempotent replay guarantee)
	// ---------------------------------------------------------------------

	@Test
	void payloadForTheSameBatchAndIndexIsByteIdenticalAcrossRuns() {
		Instant base = SampleEventClient.baseInstant("run-1");
		String first = SampleEventClient.payload("run-1", base, 7);
		String second = SampleEventClient.payload("run-1", SampleEventClient.baseInstant("run-1"), 7);

		// Same eventId and identical content: identical payload bytes → 200 on replay, not 409.
		assertThat(SampleEventClient.eventId("run-1", 7)).isEqualTo(SampleEventClient.eventId("run-1", 7));
		assertThat(second).isEqualTo(first);
	}

	@Test
	void baseInstantOfATimestampBatchIsTheParsedTimestamp() {
		assertThat(SampleEventClient.baseInstant("20261018T123456Z"))
				.isEqualTo(Instant.parse("2026-10-18T12:34:56Z"));
	}

	@Test
	void baseInstantOfACustomBatchIsDeterministicAndInsideThe2026Window() {
		Instant first = SampleEventClient.baseInstant("demo-batch");
		Instant second = SampleEventClient.baseInstant("demo-batch");
		Instant other = SampleEventClient.baseInstant("other-batch");

		assertThat(second).isEqualTo(first);
		assertThat(first).isNotEqualTo(other);
		assertThat(first).isBetween(Instant.parse("2026-01-01T00:00:00Z"),
				Instant.parse("2026-01-01T00:00:00Z").plusSeconds(365L * 24 * 60 * 60 - 1));
		// Spread: consecutive events are exactly one minute apart.
		assertThat(SampleEventClient.occurredAt(first, 0)).isNotEqualTo(SampleEventClient.occurredAt(first, 1));
	}

	/** Minimal JSON syntax guard for the hand-built (no-dependency) bodies. */
	private static Object parse(String json) {
		return new tools.jackson.databind.json.JsonMapper().readTree(json);
	}
}
