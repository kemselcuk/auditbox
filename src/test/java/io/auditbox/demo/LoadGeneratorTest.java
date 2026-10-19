package io.auditbox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the non-network parts of the JDK-only load generator (plan task T11.3,
 * decision D23): argument parsing/validation over the safe defaults, the deterministic
 * {@code load-<run-id>-<i>} workload whose byte-identical payloads make {@code --replay}
 * an idempotent replay (D4), the status classification/exit codes of the report and the
 * pure percentile math behind the latency line.
 */
class LoadGeneratorTest {

	// ---------------------------------------------------------------------
	// Configuration parsing (T11.3)
	// ---------------------------------------------------------------------

	@Test
	void defaultsAreAppliedWhenNoArgumentsAreGiven() {
		LoadGenerator.Config config = LoadGenerator.Config.parse();

		assertThat(config.baseUrl()).isEqualTo("http://localhost:8080");
		assertThat(config.apiKey()).isEqualTo("local-dev-key");
		assertThat(config.requests()).isEqualTo(2000);
		assertThat(config.concurrency()).isEqualTo(16);
		assertThat(config.replay()).isFalse();
		assertThat(config.timeoutSeconds()).isEqualTo(10);
		// The default run id is <epoch-second>-<pid>: unique per process, like
		// scripts/dedupe-demo.sh, so back-to-back runs never collide.
		assertThat(config.runId()).matches("\\d+-\\d+");
	}

	@Test
	void allFlagsCanBeOverridden() {
		LoadGenerator.Config config = LoadGenerator.Config.parse("--base-url=http://localhost:9090",
				"--api-key=other-key", "--requests=5000", "--concurrency=32", "--timeout-seconds=5",
				"--replay", "--run-id=run-1");

		assertThat(config.baseUrl()).isEqualTo("http://localhost:9090");
		assertThat(config.apiKey()).isEqualTo("other-key");
		assertThat(config.requests()).isEqualTo(5000);
		assertThat(config.concurrency()).isEqualTo(32);
		assertThat(config.replay()).isTrue();
		assertThat(config.timeoutSeconds()).isEqualTo(5);
		assertThat(config.runId()).isEqualTo("run-1");
	}

	@Test
	void replayAcceptsABareFlagAndTrueFalseValues() {
		assertThat(LoadGenerator.Config.parse("--replay").replay()).isTrue();
		assertThat(LoadGenerator.Config.parse("--replay=true").replay()).isTrue();
		assertThat(LoadGenerator.Config.parse("--replay=false").replay()).isFalse();

		assertThatThrownBy(() -> LoadGenerator.Config.parse("--replay=yes"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("--replay must be a bare flag or --replay=true/false");
	}

	@Test
	void outOfRangeAndNonNumericValuesAreRejected() {
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--requests=0"))
				.hasMessageContaining("requests must be between 1 and 100000");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--requests=100001"))
				.hasMessageContaining("requests must be between 1 and 100000");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--requests=ten"))
				.hasMessageContaining("requests must be an integer between 1 and 100000");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--concurrency=0"))
				.hasMessageContaining("concurrency must be between 1 and 512");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--concurrency=513"))
				.hasMessageContaining("concurrency must be between 1 and 512");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--timeout-seconds=0"))
				.hasMessageContaining("timeout-seconds must be between 1 and 600");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--timeout-seconds=601"))
				.hasMessageContaining("timeout-seconds must be between 1 and 600");
	}

	@Test
	void unknownAndMalformedArgumentsAreRejected() {
		assertThatThrownBy(() -> LoadGenerator.Config.parse("requests=3"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported argument");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--unknown=1"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown flag --unknown");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--requests="))
				.hasMessageContaining("blank value");
	}

	@Test
	void runIdCharsetAndLengthAreValidated() {
		assertThat(LoadGenerator.Config.parse("--run-id=Ok-9_").runId()).isEqualTo("Ok-9_");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--run-id=with space"))
				.hasMessageContaining("run-id must be 1..100 characters");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--run-id=" + "x".repeat(101)))
				.hasMessageContaining("run-id must be 1..100 characters");
	}

	@Test
	void baseUrlValidationAndNormalizationMirrorTheSampleClient() {
		// No scheme/host, or an unsupported scheme: the same usage error as the demo client.
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--base-url=localhost:8080"))
				.hasMessageContaining("base-url must be an absolute http/https URL");
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--base-url=ftp://host"))
				.hasMessageContaining("base-url must be an absolute http/https URL");
		// Trailing slashes are stripped so request paths never double the slash.
		assertThat(LoadGenerator.Config.parse("--base-url=http://localhost:8080/").baseUrl())
				.isEqualTo("http://localhost:8080");
	}

	// ---------------------------------------------------------------------
	// Deterministic workload (the D4 replay guarantee for --replay)
	// ---------------------------------------------------------------------

	@Test
	void eventIdsFollowTheLoadRunSchemeAndStayWithinTheApiLimit() {
		assertThat(LoadGenerator.eventId("1792543539-61049", 0)).isEqualTo("load-1792543539-61049-0");
		assertThat(LoadGenerator.eventId("run-1", 4999)).isEqualTo("load-run-1-4999");
		// Worst case: a 100-character run id plus the 100000th index stays within 128 chars.
		assertThat(LoadGenerator.eventId("x".repeat(100), 99999)).hasSizeLessThanOrEqualTo(128);
	}

	@Test
	void apiKeyWithControlCharactersIsAUsageError() {
		// A newline would make HttpClient reject every request at runtime; fail as a usage
		// error up front instead (review F5).
		assertThatThrownBy(() -> LoadGenerator.Config.parse("--api-key=abc\ndef"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control characters");
	}

	@Test
	void payloadDependsOnlyOnRunIdAndIndexSoReplayIsByteIdentical() {
		Instant base = SampleEventClient.baseInstant("run-1");
		String first = LoadGenerator.payload("run-1", base, 7);
		String second = LoadGenerator.payload("run-1", SampleEventClient.baseInstant("run-1"), 7);

		// Wave 1 and wave 2 send the same eventId with identical content: idempotent 200,
		// never 409 (D4). The eventId is deterministic and distinct per index.
		assertThat(LoadGenerator.eventId("run-1", 7)).isEqualTo("load-run-1-7");
		assertThat(LoadGenerator.eventId("run-1", 7)).isNotEqualTo(LoadGenerator.eventId("run-1", 8));
		assertThat(second).isEqualTo(first);
	}

	@Test
	void payloadsAreValidJsonWithVariedFieldsAndMetadataOnEveryThirdEvent() {
		Instant base = SampleEventClient.baseInstant("run-1");
		String withMetadata = LoadGenerator.payload("run-1", base, 3); // 3 % 3 == 0
		String withoutMetadata = LoadGenerator.payload("run-1", base, 4);

		assertThat(withMetadata).startsWith("{\"eventId\":\"load-run-1-3\"");
		assertThat(withMetadata).contains("\"userId\":\"load-user-3\"");
		assertThat(withMetadata).contains("\"eventType\":\"account.updated\""); // 3 % 4
		assertThat(withMetadata).contains("\"source\":\"core-banking\""); // 3 % 3 = 0
		assertThat(withMetadata).contains("\"occurredAt\":\"" + base.minusSeconds(3) + "\"");
		assertThat(withMetadata).contains("\"metadata\":{\"amount\":13.50,\"currency\":\"EUR\"}");
		assertThat(withoutMetadata).doesNotContain("metadata");
		// Both bodies are valid JSON — reject the hand-built (no-dependency) builder on regression.
		assertThat(new JsonMapper().readTree(withMetadata)).isNotNull();
		assertThat(new JsonMapper().readTree(withoutMetadata)).isNotNull();
	}

	@Test
	void occurredAtIsSpreadOneSecondPerIndexBelowTheRunBase() {
		Instant base = SampleEventClient.baseInstant("run-1");
		String first = LoadGenerator.payload("run-1", base, 0);
		String second = LoadGenerator.payload("run-1", base, 1);

		assertThat(first).contains("\"occurredAt\":\"" + base.minusSeconds(0) + "\"");
		assertThat(second).contains("\"occurredAt\":\"" + base.minusSeconds(1) + "\"");
		assertThat(first).isNotEqualTo(second);
	}

	// ---------------------------------------------------------------------
	// Status classification and exit codes
	// ---------------------------------------------------------------------

	@Test
	void classifyGroupsStatusesIntoTheReportBuckets() {
		assertThat(LoadGenerator.classify(200)).isEqualTo("200");
		assertThat(LoadGenerator.classify(201)).isEqualTo("201");
		assertThat(LoadGenerator.classify(409)).isEqualTo("409");
		assertThat(LoadGenerator.classify(429)).isEqualTo("429");
		assertThat(LoadGenerator.classify(500)).isEqualTo("5xx");
		assertThat(LoadGenerator.classify(599)).isEqualTo("5xx");
		// Everything else, including other 4xx, 3xx and 2xx statuses.
		assertThat(LoadGenerator.classify(400)).isEqualTo("other");
		assertThat(LoadGenerator.classify(404)).isEqualTo("other");
		assertThat(LoadGenerator.classify(302)).isEqualTo("other");
		assertThat(LoadGenerator.classify(204)).isEqualTo("other");
		// Connection failures (no HTTP status at all) use the shared -1 convention.
		assertThat(LoadGenerator.classify(-1)).isEqualTo("error");
	}

	@Test
	void formatStatusesSortsTheBucketsIntoOneCompactLine() {
		assertThat(LoadGenerator.formatStatuses(Map.of(201, 5000))).isEqualTo("201 x5000");
		assertThat(LoadGenerator.formatStatuses(Map.of(201, 3, 200, 2, -1, 1)))
				.isEqualTo("200 x2, 201 x3, error x1");
		assertThat(LoadGenerator.formatStatuses(Map.of())).isEqualTo("none");
	}

	@Test
	void exitCodeIsZeroOnlyWhenEveryRequestWasAnswered2xx() {
		// Nothing completed at all is a failure, not a vacuous success (review F5).
		assertThat(LoadGenerator.exitCode(Map.of())).isEqualTo(1);
		assertThat(LoadGenerator.exitCode(Map.of(201, 3, 200, 2))).isZero();
		assertThat(LoadGenerator.exitCode(Map.of(204, 1))).isZero();
		// Any non-2xx response fails the run, 3xx included (review F5).
		assertThat(LoadGenerator.exitCode(Map.of(302, 1))).isEqualTo(1);
		assertThat(LoadGenerator.exitCode(Map.of(-1, 1))).isEqualTo(1);
		assertThat(LoadGenerator.exitCode(Map.of(429, 7))).isEqualTo(1);
		assertThat(LoadGenerator.exitCode(Map.of(500, 1))).isEqualTo(1);
		assertThat(LoadGenerator.exitCode(Map.of(201, 4, 401, 1))).isEqualTo(1);
	}

	// ---------------------------------------------------------------------
	// Percentile math (the latency report's pure core)
	// ---------------------------------------------------------------------

	@Test
	void everyPercentileOfASingleValueSampleIsThatValue() {
		long[] sample = { 42 };
		assertThat(LoadGenerator.percentile(sample, 0)).isEqualTo(42);
		assertThat(LoadGenerator.percentile(sample, 50)).isEqualTo(42);
		assertThat(LoadGenerator.percentile(sample, 95)).isEqualTo(42);
		assertThat(LoadGenerator.percentile(sample, 99)).isEqualTo(42);
		assertThat(LoadGenerator.percentile(sample, 100)).isEqualTo(42);
	}

	@Test
	void percentilesInterpolateLinearlyOnTheSortedSamples() {
		long[] sample = { 10, 20, 30, 40 };
		assertThat(LoadGenerator.percentile(sample, 0)).isEqualTo(10);
		assertThat(LoadGenerator.percentile(sample, 100)).isEqualTo(40);
		// rank 1.5 -> exactly between 20 and 30.
		assertThat(LoadGenerator.percentile(sample, 50)).isEqualTo(25);
		// rank 0.75 -> 17.5, rounded half-up to whole nanoseconds.
		assertThat(LoadGenerator.percentile(sample, 25)).isEqualTo(18);

		// Odd counts hit the middle element exactly...
		assertThat(LoadGenerator.percentile(new long[] { 5, 10, 15, 20, 25 }, 50)).isEqualTo(15);

		// ...and the report's tail percentiles land on the documented ranks (1..21: rank
		// 19 for p95, 19.8 for p99).
		long[] twentyOne = LongStream.rangeClosed(1, 21).toArray();
		assertThat(LoadGenerator.percentile(twentyOne, 50)).isEqualTo(11);
		assertThat(LoadGenerator.percentile(twentyOne, 95)).isEqualTo(20);
		assertThat(LoadGenerator.percentile(twentyOne, 99)).isEqualTo(21);
	}

	@Test
	void percentileOfAnEmptySampleIsRejected() {
		assertThatThrownBy(() -> LoadGenerator.percentile(new long[0], 50))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("empty sample");
	}

	@Test
	void sortedRecordedDropsUnrecordedEntriesAndSorts() {
		assertThat(LoadGenerator.sortedRecorded(new long[] { 5, -1, 3, -1, 9 }))
				.containsExactly(3, 5, 9);
		assertThat(LoadGenerator.sortedRecorded(new long[] { -1, -1 })).isEmpty();
	}

	@Test
	void latencyLineFallsBackToNAWhenNothingCompleted() {
		assertThat(LoadGenerator.formatLatencies(new long[] { -1, -1 })).isEqualTo("n/a (no completed requests)");
		assertThat(LoadGenerator.formatLatencies(new long[] { 2_000_000L, 4_000_000L, -1 }))
				.isEqualTo("p50 3.0 ms | p95 3.9 ms | p99 4.0 ms | max 4.0 ms (2 samples)");
	}
}
