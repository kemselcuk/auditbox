package io.auditbox.demo;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * JDK-only load generator for the events API (plan task T11.3, decision D23), a companion
 * to {@link SampleEventClient} in the same demo package: it uses only the JDK
 * ({@code java.net.http.HttpClient}, hand-built JSON, Java 21 virtual threads), so it adds
 * no dependency and no external tool (no k6/Gatling). It is a tool, not production code:
 * nothing in the app's request path refers to it.
 *
 * <pre>
 * ./mvnw compile
 * java -cp target/classes io.auditbox.demo.LoadGenerator --requests=5000 --concurrency=32 [--replay]
 * </pre>
 * or via the thin wrapper {@code scripts/load-test.sh} (env: {@code BASE_URL},
 * {@code API_KEY}, {@code REQUESTS}, {@code CONCURRENCY}, {@code REPLAY}, {@code RUN_ID},
 * {@code TIMEOUT_SECONDS}).
 *
 * <p>Flags (all optional): {@code --base-url=URL} (default {@code http://localhost:8080}),
 * {@code --api-key=KEY} (default {@code local-dev-key}), {@code --requests=N} (default 2000,
 * 1..100000 unique events), {@code --concurrency=C} (default 16, 1..512 — the maximum
 * number of in-flight requests), {@code --timeout-seconds=S} (default 10, 1..600),
 * {@code --replay} (send every eventId a second time after the first wave) and
 * {@code --run-id=ID} (default {@code <epoch-second>-<pid>} of the process, like
 * {@code scripts/dedupe-demo.sh}, so back-to-back runs never collide).
 *
 * <p>Workload: {@code POST /api/v1/events} with the unique, deterministic eventIds
 * {@code load-<run-id>-<i>}. Like the sample client's batch (D4), the payload of event i
 * depends only on the pair {@code (run-id, i)} — never on the wall clock at send time — so
 * with {@code --replay} the second wave resends byte-identical content and must come back
 * as idempotent {@code 200}s. Payloads rotate through four event types, three sources, 25
 * users, one {@code occurredAt} second per index and a small metadata object on every third
 * event.
 *
 * <p>Concurrency bound: exactly {@code --concurrency} worker virtual threads pull the next
 * index from a shared cursor, so no more than {@code --concurrency} requests are ever in
 * flight — the reported concurrency IS the actual bound.
 *
 * <p>Measurement: the latency of every completed request is taken around the blocking
 * {@code HttpClient.send}. The report prints, per wave and in total: the completed count,
 * the wall time and throughput (completed / wall seconds), the status distribution grouped
 * into {@code 200/201/409/429/5xx/other/connection errors} and the latency p50/p95/p99/max
 * over the sorted samples (linear interpolation, {@link #percentile} — pure JDK math, no
 * library).
 *
 * <p>Authentication and rate limiting: the generator always sends {@code X-API-Key} and
 * never bypasses the app's per-source rate limiter (D19, default 300 requests/min per
 * source). A load run therefore needs the app started with a raised
 * {@code RATE_LIMIT_REQUESTS_PER_MINUTE} — the report says so when 429s appear.
 *
 * <p>Exit status: {@code 0} when every request was answered with {@code 2xx}, {@code 1}
 * when at least one connection failed or any response status was {@code >= 400}, and
 * {@code 2} for usage errors (misformatted or invalid arguments). All reported numbers are
 * machine/stack-specific local baselines (D23), not an SLA.
 */
public final class LoadGenerator {

	/**
	 * Raw status recorded for a request that never got an HTTP response (connection
	 * refused, timeout, interrupted) — the same convention as {@link SampleEventClient}.
	 */
	static final int CONNECTION_ERROR = -1;

	private static final String[] EVENT_TYPES =
			{ "payment.settled", "payment.initiated", "fraud.flagged", "account.updated" };

	private static final String[] SOURCES = { "core-banking", "web-shop", "mobile-app" };

	/** Upper bounds that keep one run sane; documented in {@link #usage}. */
	private static final int MAX_REQUESTS = 100_000;
	private static final int MAX_CONCURRENCY = 512;
	private static final int MAX_TIMEOUT_SECONDS = 600;

	/** Parsed command line of one generator run. */
	record Config(String baseUrl, String apiKey, int requests, int concurrency, boolean replay,
			int timeoutSeconds, String runId) {

		static final String DEFAULT_BASE_URL = "http://localhost:8080";
		static final String DEFAULT_API_KEY = "local-dev-key";
		static final int DEFAULT_REQUESTS = 2000;
		static final int DEFAULT_CONCURRENCY = 16;
		static final int DEFAULT_TIMEOUT_SECONDS = 10;

		/**
		 * Parses {@code --flag=value} arguments over the safe defaults ({@code --replay} is
		 * also accepted bare or as {@code --replay=true/false}). Unknown flags, blank
		 * values, numbers outside the documented ranges, a non-absolute/non-http(s) base
		 * URL or a run id outside {@code [A-Za-z0-9_-]} raise {@link IllegalArgumentException}
		 * (usage error, exit code 2). The run-id charset keeps every derived eventId within
		 * the 128-character API limit and free of JSON escaping.
		 */
		static Config parse(String... args) {
			String baseUrl = DEFAULT_BASE_URL;
			String apiKey = DEFAULT_API_KEY;
			int requests = DEFAULT_REQUESTS;
			int concurrency = DEFAULT_CONCURRENCY;
			boolean replay = false;
			int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
			String runId = null;
			for (String arg : args) {
				if (arg.equals("--replay")) {
					replay = true;
					continue;
				}
				int split = arg.indexOf('=');
				if (!arg.startsWith("--") || split < 3) {
					throw new IllegalArgumentException("Unsupported argument '" + arg
							+ "': expected --base-url/--api-key/--requests/--concurrency/--timeout-seconds/--run-id=value or --replay");
				}
				String name = arg.substring(2, split);
				String value = arg.substring(split + 1);
				if (value.isBlank()) {
					throw new IllegalArgumentException("Flag --" + name + " must not have a blank value");
				}
				switch (name) {
					case "base-url" -> baseUrl = value;
					case "api-key" -> apiKey = value;
					case "requests" -> requests = parseIntInRange("requests", value, 1, MAX_REQUESTS);
					case "concurrency" -> concurrency = parseIntInRange("concurrency", value, 1, MAX_CONCURRENCY);
					case "timeout-seconds" -> timeoutSeconds = parseIntInRange("timeout-seconds", value, 1, MAX_TIMEOUT_SECONDS);
					case "run-id" -> runId = parseRunId(value);
					case "replay" -> replay = parseReplayValue(value);
					default -> throw new IllegalArgumentException("Unknown flag --" + name
							+ ": expected --base-url/--api-key/--requests/--concurrency/--timeout-seconds/--run-id/--replay");
				}
			}
			if (runId == null) {
				// Per-process default (epoch second + PID), like scripts/dedupe-demo.sh:
				// unique even when two runs start within the same second.
				runId = Instant.now().getEpochSecond() + "-" + ProcessHandle.current().pid();
			}
			return new Config(parseBaseUrl(baseUrl), apiKey, requests, concurrency, replay,
					timeoutSeconds, runId);
		}

		/**
		 * Validates and normalizes {@code --base-url}: it must be an absolute {@code http}
		 * or {@code https} URI with a host; trailing slashes are stripped so request paths
		 * never become {@code //api/v1/events}. Mirrors {@link SampleEventClient}.
		 */
		private static String parseBaseUrl(String value) {
			URI uri;
			try {
				uri = URI.create(value);
			}
			catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
						"--base-url must be an absolute http/https URL, got '" + value + "'");
			}
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!uri.isAbsolute() || uri.getHost() == null
					|| !(scheme.equals("http") || scheme.equals("https"))) {
				throw new IllegalArgumentException(
						"--base-url must be an absolute http/https URL, got '" + value + "'");
			}
			String normalized = value;
			while (normalized.endsWith("/")) {
				normalized = normalized.substring(0, normalized.length() - 1);
			}
			return normalized;
		}

		private static int parseIntInRange(String name, String value, int min, int max) {
			int parsed;
			try {
				parsed = Integer.parseInt(value);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						name + " must be an integer between " + min + " and " + max + ", got '" + value + "'");
			}
			if (parsed < min || parsed > max) {
				throw new IllegalArgumentException(
						name + " must be between " + min + " and " + max + ", got " + parsed);
			}
			return parsed;
		}

		private static String parseRunId(String value) {
			if (value.length() > 100 || !value.chars().allMatch(c -> (c >= 'a' && c <= 'z')
					|| (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
				throw new IllegalArgumentException(
						"run-id must be 1..100 characters from [A-Za-z0-9_-], got '" + value + "'");
			}
			return value;
		}

		private static boolean parseReplayValue(String value) {
			if (value.equals("true")) {
				return true;
			}
			if (value.equals("false")) {
				return false;
			}
			throw new IllegalArgumentException(
					"--replay must be a bare flag or --replay=true/false, got '" + value + "'");
		}
	}

	private LoadGenerator() {
	}

	public static void main(String[] args) {
		Config config;
		try {
			config = Config.parse(args);
		}
		catch (IllegalArgumentException e) {
			System.err.println("Error: " + e.getMessage());
			usage(System.err);
			System.exit(2);
			return;
		}
		int exit = run(config, System.out);
		if (exit != 0) {
			System.exit(exit);
		}
	}

	/**
	 * Runs the configured workload against the running stack and prints the report.
	 *
	 * @return the process exit code: {@code 0} when every request was answered with
	 * {@code 2xx}, {@code 1} when at least one connection failed or any response status was
	 * {@code >= 400} (see {@link #exitCode(Map)}).
	 */
	private static int run(Config config, PrintStream out) {
		// One payload per index, built once: wave 1 and (with --replay) wave 2 resend the
		// exact same bytes, which is what makes the replay idempotent (D4).
		Instant base = SampleEventClient.baseInstant(config.runId());
		String[] payloads = new String[config.requests()];
		for (int i = 0; i < payloads.length; i++) {
			payloads[i] = payload(config.runId(), base, i);
		}
		int totalRequests = payloads.length * (config.replay() ? 2 : 1);

		out.println("AuditBox load generator");
		out.println("  base-url    : " + config.baseUrl());
		out.println("  run id      : " + config.runId() + "  (eventIds are load-<run id>-<i>)");
		out.println("  requests    : " + config.requests() + " unique events"
				+ (config.replay() ? " + the same " + config.requests() + " replayed afterwards" : ""));
		out.println("  concurrency : " + config.concurrency() + " (max in-flight requests)");
		out.println("  timeout     : " + config.timeoutSeconds() + " s per request");
		out.println("  api key     : configured (not echoed back)");
		out.println();

		Map<Integer, Integer> totalCounts = new TreeMap<>();
		long completed = 0;
		long runStart = System.nanoTime();
		try (HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
				.build()) {
			out.println("Phase 1: fresh writes (expecting 201) — " + payloads.length
					+ " requests, " + config.concurrency() + " in flight ...");
			long[] freshLatencies = new long[payloads.length];
			PhaseResult fresh = runPhase(config, client, payloads, freshLatencies);
			printPhaseSummary(out, config, fresh);
			completed += fresh.completed();
			fresh.statusCounts().forEach((status, count) -> totalCounts.merge(status, count, Integer::sum));

			if (config.replay()) {
				out.println();
				out.println("Phase 2: idempotent replay of the same eventIds (expecting 200) — "
						+ payloads.length + " requests, " + config.concurrency() + " in flight ...");
				long[] replayLatencies = new long[payloads.length];
				PhaseResult replay = runPhase(config, client, payloads, replayLatencies);
				printPhaseSummary(out, config, replay);
				completed += replay.completed();
				replay.statusCounts().forEach((status, count) -> totalCounts.merge(status, count, Integer::sum));
			}
		}
		long wallNanos = System.nanoTime() - runStart;

		out.println();
		out.println("Total");
		out.println("  completed : " + completed + " of " + totalRequests + " in "
				+ formatSeconds(wallNanos) + " s (" + formatRate(completed, wallNanos) + ")");
		out.println("  statuses  : " + formatStatuses(totalCounts));
		if (totalCounts.containsKey(429)) {
			out.println("  note      : 429s mean the app's per-source rate limiter (D19, default 300/min) throttled");
			out.println("              this run — restart the app with RATE_LIMIT_REQUESTS_PER_MINUTE raised (README 'Load test').");
		}
		if (totalCounts.containsKey(CONNECTION_ERROR)) {
			out.println("  note      : connection errors usually mean the app is not reachable at " + config.baseUrl() + ".");
		}
		return exitCode(totalCounts);
	}

	/**
	 * One wave of POSTs over the precomputed payloads, recorded into {@code latencies}
	 * (index {@code -1} = request never completed). Exactly {@code --concurrency} worker
	 * virtual threads pull the next index from a shared cursor, so no more than
	 * {@code --concurrency} requests are ever in flight.
	 */
	private static PhaseResult runPhase(Config config, HttpClient client, String[] payloads, long[] latencies) {
		Arrays.fill(latencies, -1L);
		ConcurrentHashMap<Integer, Integer> statusCounts = new ConcurrentHashMap<>();
		AtomicInteger cursor = new AtomicInteger();
		long start = System.nanoTime();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int worker = 0; worker < config.concurrency(); worker++) {
				executor.submit(() -> {
					int index;
					while ((index = cursor.getAndIncrement()) < payloads.length) {
						sendOne(config, client, payloads[index], latencies, index, statusCounts);
					}
				});
			}
		} // close() waits for every worker to finish.
		return new PhaseResult(statusCounts, latencies, System.nanoTime() - start);
	}

	/** One blocking POST with the run's key; records status and latency, never throws. */
	private static void sendOne(Config config, HttpClient client, String payload, long[] latencies,
			int index, ConcurrentHashMap<Integer, Integer> statusCounts) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v1/events"))
				.header("Content-Type", "application/json")
				.header("X-API-Key", config.apiKey())
				.timeout(Duration.ofSeconds(config.timeoutSeconds()))
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();
		long began = System.nanoTime();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			latencies[index] = System.nanoTime() - began;
			statusCounts.merge(response.statusCode(), 1, Integer::sum);
		}
		catch (java.io.IOException e) {
			// Connection-level failure (refused, reset, timeout): record as status -1.
			statusCounts.merge(CONNECTION_ERROR, 1, Integer::sum);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			statusCounts.merge(CONNECTION_ERROR, 1, Integer::sum);
		}
	}

	/** Outcome of one wave: raw status counts, per-request latencies (-1 = not recorded), wall time. */
	private record PhaseResult(ConcurrentHashMap<Integer, Integer> statusCounts, long[] latenciesNanos,
			long wallNanos) {

		long completed() {
			return Arrays.stream(latenciesNanos).filter(nanos -> nanos >= 0).count();
		}
	}

	private static void printPhaseSummary(PrintStream out, Config config, PhaseResult phase) {
		out.println("  completed : " + phase.completed() + " of " + config.requests() + " in "
				+ formatSeconds(phase.wallNanos()) + " s (" + formatRate(phase.completed(), phase.wallNanos()) + ")");
		out.println("  statuses  : " + formatStatuses(phase.statusCounts()));
		out.println("  latency   : " + formatLatencies(phase.latenciesNanos()));
	}

	/**
	 * Prints how to use the generator (also the error aid for misformatted arguments).
	 */
	private static void usage(PrintStream out) {
		out.println("Usage: java -cp target/classes io.auditbox.demo.LoadGenerator [--flag=value ...]");
		out.println("Flags (all optional):");
		out.println("  --base-url=URL        AuditBox base URL (default " + Config.DEFAULT_BASE_URL + ")");
		out.println("  --api-key=KEY         X-API-Key value (default " + Config.DEFAULT_API_KEY + ")");
		out.println("  --requests=N          unique events to POST (default " + Config.DEFAULT_REQUESTS + ", 1.." + MAX_REQUESTS + ")");
		out.println("  --concurrency=C       max in-flight requests (default " + Config.DEFAULT_CONCURRENCY + ", 1.." + MAX_CONCURRENCY + ")");
		out.println("  --timeout-seconds=S   per-request timeout (default " + Config.DEFAULT_TIMEOUT_SECONDS + ", 1.." + MAX_TIMEOUT_SECONDS + ")");
		out.println("  --replay              send every eventId a second time after the first wave (expects 200s)");
		out.println("  --run-id=ID           eventIds are load-<run-id>-<i>");
		out.println("                        (default: <epoch-second>-<pid>, unique per process)");
		out.println("Exit status: 0 when every request got 2xx; 1 when at least one connection failed");
		out.println("             or any response status was >= 400; 2 on usage errors (this text)");
		out.println("Note: the app's per-source rate limiter defaults to 300 requests/min (D19). For load runs");
		out.println("      start the app with RATE_LIMIT_REQUESTS_PER_MINUTE raised (README section 'Load test').");
	}

	// ---------------------------------------------------------------------
	// Pure helpers (unit-tested without any network)
	// ---------------------------------------------------------------------

	/**
	 * p-th percentile (0..100, inclusive bounds) of an ASCENDING sample array by linear
	 * interpolation between the closest ranks: {@code rank = p/100 * (n-1)}, the value
	 * interpolated between {@code sorted[floor(rank)]} and {@code sorted[ceil(rank)]} and
	 * rounded half-up to whole nanoseconds. For a single sample every percentile is that
	 * value. Pure JDK math (D23: no statistics library), so the report numbers are pinned
	 * by unit tests.
	 */
	static long percentile(long[] sortedAscending, double p) {
		int n = sortedAscending.length;
		if (n == 0) {
			throw new IllegalArgumentException("percentile of an empty sample is undefined");
		}
		if (p <= 0) {
			return sortedAscending[0];
		}
		if (p >= 100) {
			return sortedAscending[n - 1];
		}
		double rank = (p / 100.0) * (n - 1);
		int lower = (int) rank;
		int upper = Math.min(lower + 1, n - 1);
		double fraction = rank - lower;
		return Math.round(sortedAscending[lower] + (sortedAscending[upper] - sortedAscending[lower]) * fraction);
	}

	/** Sorted copy of the recorded latencies ({@code -1} marks a request that never completed). */
	static long[] sortedRecorded(long[] samplesNanos) {
		return Arrays.stream(samplesNanos).filter(nanos -> nanos >= 0).sorted().toArray();
	}

	/** The compact latency line of the report: p50/p95/p99/max over the recorded samples. */
	static String formatLatencies(long[] samplesNanos) {
		long[] recorded = sortedRecorded(samplesNanos);
		if (recorded.length == 0) {
			return "n/a (no completed requests)";
		}
		return "p50 " + formatMillis(percentile(recorded, 50))
				+ " | p95 " + formatMillis(percentile(recorded, 95))
				+ " | p99 " + formatMillis(percentile(recorded, 99))
				+ " | max " + formatMillis(recorded[recorded.length - 1])
				+ " (" + recorded.length + " samples)";
	}

	/**
	 * Pure exit-code mapping of one run's status counts (connection failures are recorded
	 * as status {@link #CONNECTION_ERROR}): {@code 0} when everything was answered 2xx,
	 * {@code 1} when at least one connection failed or any response status was {@code >= 400}.
	 */
	static int exitCode(Map<Integer, Integer> statusCounts) {
		boolean failed = statusCounts.entrySet().stream()
				.anyMatch(entry -> entry.getKey() == CONNECTION_ERROR || entry.getKey() >= 400);
		return failed ? 1 : 0;
	}

	/**
	 * Groups a raw HTTP status (or {@link #CONNECTION_ERROR}) into the report buckets the
	 * brief names: the exact statuses this API emits (200/201/409/429), every other 5xx,
	 * everything else ("other") and connection failures ("error").
	 */
	static String classify(int statusCode) {
		return switch (statusCode) {
			case CONNECTION_ERROR -> "error";
			case 200 -> "200";
			case 201 -> "201";
			case 409 -> "409";
			case 429 -> "429";
			default -> statusCode >= 500 && statusCode <= 599 ? "5xx" : "other";
		};
	}

	/** One compact statuses line, e.g. {@code 200 x5000, 201 x2500, error x3}. */
	static String formatStatuses(Map<Integer, Integer> statusCounts) {
		Map<String, Long> grouped = new TreeMap<>();
		statusCounts.forEach((status, count) -> grouped.merge(classify(status), (long) count, Long::sum));
		if (grouped.isEmpty()) {
			return "none";
		}
		return grouped.entrySet().stream()
				.map(entry -> entry.getKey() + " x" + entry.getValue())
				.collect(Collectors.joining(", "));
	}

	static String formatSeconds(long nanos) {
		return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000_000.0);
	}

	static String formatMillis(long nanos) {
		return String.format(Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
	}

	/** Throughput over a wall time, e.g. {@code 778.8 req/s} ({@code n/a} before any request). */
	static String formatRate(long completed, long wallNanos) {
		double seconds = wallNanos / 1_000_000_000.0;
		if (seconds <= 0 || completed <= 0) {
			return "n/a";
		}
		return String.format(Locale.ROOT, "%.1f req/s", completed / seconds);
	}

	/** Deterministic eventId of the i-th event of a run: {@code load-<run-id>-<i>}. */
	static String eventId(String runId, int i) {
		return "load-" + runId + "-" + i;
	}

	/**
	 * Hand-built JSON body of the i-th event of a run, byte-identical for the same
	 * {@code (runId, i)} across waves and processes: one of four event types, one of three
	 * sources, 25 rotating users, {@code occurredAt} one second per index below the run's
	 * base instant and a small metadata object on every third event. The validated run-id
	 * charset ({@code [A-Za-z0-9_-]}) keeps every interpolated value JSON-safe; the eventId
	 * is still escaped defensively (shared helper of the demo package). The base instant is
	 * the run-id mapping of {@link SampleEventClient#baseInstant}.
	 */
	static String payload(String runId, Instant base, int i) {
		String userId = "load-user-" + (i % 25);
		String eventType = EVENT_TYPES[i % EVENT_TYPES.length];
		String source = SOURCES[i % SOURCES.length];
		String occurredAt = base.minusSeconds(i).toString();
		String metadata = i % 3 == 0
				? ",\"metadata\":{\"amount\":" + (10 + (i % 50)) + "." + (i % 2 == 0 ? "00" : "50") + ",\"currency\":\"EUR\"}"
				: "";
		return "{\"eventId\":\"" + SampleEventClient.jsonEscape(eventId(runId, i)) + "\""
				+ ",\"userId\":\"" + userId + "\""
				+ ",\"eventType\":\"" + eventType + "\""
				+ ",\"source\":\"" + source + "\""
				+ ",\"occurredAt\":\"" + occurredAt + "\""
				+ metadata + "}";
	}
}
