package io.auditbox.demo;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small standalone sample-event client for the demo package (plan task T6.1, decision D8).
 * It uses only the JDK ({@code java.net.http.HttpClient}) and builds the JSON bodies by
 * hand, so it runs against a locally started AuditBox without any extra dependency:
 *
 * <pre>
 * ./mvnw compile
 * java -cp target/classes io.auditbox.demo.SampleEventClient [--flag=value ...]
 * </pre>
 *
 * <p>Flags (all optional): {@code --base-url=URL} (default {@code http://localhost:8080}),
 * {@code --api-key=KEY} (default {@code local-dev-key}, the safe local development key),
 * {@code --count=N} (default 20, 1..10000) and {@code --batch=ID} (default: a UTC timestamp
 * so every plain run is a new batch).
 *
 * <p>Determinism within a batch: the eventIds are {@code demo-<batch>-<i>} and the whole
 * payload (including {@code occurredAt}) depends only on the pair {@code (batch, i)}, never
 * on the wall clock at send time. Re-running the same batch therefore replays identical
 * content and receives idempotent {@code 200} responses instead of conflicts (D4). The base
 * instant is the batch string itself when it is a {@code yyyyMMdd'T'HHmmss'Z'} timestamp (so
 * the default run's events are spread over the most recent minutes) and, for any other batch
 * id, a fixed function of the string over a deterministic {@code 2026} reference window (a
 * custom batch id is not a wall-clock value, so no recentness can be promised there).
 */
public final class SampleEventClient {

	/** Default run output format for the generated batch id (UTC). */
	private static final DateTimeFormatter DEFAULT_BATCH_FORMAT =
			DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

	/** Reference instant for hash-derived base instants of custom (non-timestamp) batch ids. */
	private static final Instant CUSTOM_BATCH_REFERENCE = Instant.parse("2026-01-01T00:00:00Z");

	/** Deterministic span, in minutes, over which custom batch ids map into the 2026 window. */
	private static final long CUSTOM_BATCH_SPAN_MINUTES = 365L * 24 * 60;

	private static final String[] EVENT_TYPES =
			{ "payment.settled", "payment.initiated", "fraud.flagged", "account.updated" };

	private static final String[] SOURCES = { "core-banking", "web-shop", "mobile-app" };

	/** Maximum number of events one client run may send (plain demo tool, not a load test). */
	private static final int MAX_COUNT = 10_000;

	/** Network timeout per POST; a demo against a local app should never hang. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/** Parsed command line of one client run. */
	record Config(String baseUrl, String apiKey, int count, String batch) {

		static final String DEFAULT_BASE_URL = "http://localhost:8080";
		static final String DEFAULT_API_KEY = "local-dev-key";
		static final int DEFAULT_COUNT = 20;

		/**
		 * Parses {@code --flag=value} arguments over the safe defaults. Unknown flags, blank
		 * values or a count outside 1..10000 raise {@link IllegalArgumentException}; batch
		 * ids are restricted to 1..100 characters from {@code [A-Za-z0-9_-]} so the derived
		 * eventIds need no JSON escaping and stay within the 128-character API limit.
		 */
		static Config parse(String... args) {
			String baseUrl = DEFAULT_BASE_URL;
			String apiKey = DEFAULT_API_KEY;
			int count = DEFAULT_COUNT;
			String batch = null;
			for (String arg : args) {
				int split = arg.indexOf('=');
				if (!arg.startsWith("--") || split < 3) {
					throw new IllegalArgumentException(
							"Unsupported argument '" + arg + "': expected --base-url/--api-key/--count/--batch=value");
				}
				String name = arg.substring(2, split);
				String value = arg.substring(split + 1);
				if (value.isBlank()) {
					throw new IllegalArgumentException("Flag --" + name + " must not have a blank value");
				}
				switch (name) {
					case "base-url" -> baseUrl = value;
					case "api-key" -> apiKey = value;
					case "count" -> count = parseCount(value);
					case "batch" -> batch = parseBatch(value);
					default -> throw new IllegalArgumentException(
							"Unknown flag --" + name + ": expected --base-url/--api-key/--count/--batch");
				}
			}
			if (batch == null) {
				batch = LocalDateTime.now(ZoneOffset.UTC).format(DEFAULT_BATCH_FORMAT);
			}
			return new Config(baseUrl, apiKey, count, batch);
		}

		private static int parseCount(String value) {
			int parsed;
			try {
				parsed = Integer.parseInt(value);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("count must be a positive integer, got '" + value + "'");
			}
			if (parsed < 1) {
				throw new IllegalArgumentException("count must be at least 1 (also for a demo), got " + parsed);
			}
			if (parsed > MAX_COUNT) {
				throw new IllegalArgumentException("count must not exceed " + MAX_COUNT + ", got " + parsed);
			}
			return parsed;
		}

		private static String parseBatch(String value) {
			if (value.length() > 100 || !value.chars().allMatch(c -> (c >= 'a' && c <= 'z')
					|| (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
				throw new IllegalArgumentException(
						"batch must be 1..100 characters from [A-Za-z0-9_-], got '" + value + "'");
			}
			return value;
		}
	}

	private SampleEventClient() {
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
		run(config, System.out);
	}

	/** Sends {@code count} sample events sequentially and prints a per-run summary. */
	private static void run(Config config, PrintStream out) {
		Instant base = baseInstant(config.batch());
		Map<Integer, Integer> statusCounts = new TreeMap<>();
		List<String> eventIds = new ArrayList<>(config.count());
		out.println("AuditBox sample-event client");
		out.println("  base-url : " + config.baseUrl());
		out.println("  batch    : " + config.batch());
		out.println("  count    : " + config.count());
		out.println();

		try (HttpClient client = HttpClient.newHttpClient()) {
			for (int i = 0; i < config.count(); i++) {
				String eventId = eventId(config.batch(), i);
				String payload = payload(config.batch(), base, i);
				HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v1/events"))
						.header("Content-Type", "application/json")
						.header("X-API-Key", config.apiKey())
						.timeout(REQUEST_TIMEOUT)
						.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
						.build();
				try {
					HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
					statusCounts.merge(response.statusCode(), 1, Integer::sum);
					eventIds.add(eventId);
					out.println("  [" + response.statusCode() + "] " + eventId);
				}
				catch (java.io.IOException e) {
					// Connection-level failure: record as status -1 and keep sending the rest.
					statusCounts.merge(-1, 1, Integer::sum);
					out.println("  [error] " + eventId + ": " + e.getMessage());
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					out.println("  aborted: " + e.getMessage());
					break;
				}
			}
		}

		out.println();
		out.println("Summary");
		out.println("  events sent : " + eventIds.size() + " of " + config.count());
		for (Map.Entry<Integer, Integer> entry : statusCounts.entrySet()) {
			String label = entry.getKey() == -1 ? "connection failures" : "HTTP " + entry.getKey();
			out.println("  " + label + ": " + entry.getValue());
		}
		out.println("  eventIds    : " + String.join(", ", eventIds));
	}

	/**
	 * Prints how to use the client (also the error aid for misformatted arguments).
	 */
	private static void usage(PrintStream out) {
		out.println("Usage: java -cp target/classes io.auditbox.demo.SampleEventClient [--flag=value ...]");
		out.println("Flags (all optional):");
		out.println("  --base-url=URL   AuditBox base URL (default " + Config.DEFAULT_BASE_URL + ")");
		out.println("  --api-key=KEY    X-API-Key value (default " + Config.DEFAULT_API_KEY + ")");
		out.println("  --count=N        number of sample events (default " + Config.DEFAULT_COUNT + ")");
		out.println("  --batch=ID       idempotency batch id; eventIds are demo-<batch>-<i>");
		out.println("                   (default: a UTC timestamp so each plain run is a new batch)");
		out.println("Example: --count=10 --batch=demo-run-1 (rerun the same batch for idempotent 200s)");
	}

	// ---------------------------------------------------------------------
	// Pure helpers (unit-tested without any network)
	// ---------------------------------------------------------------------

	/** Deterministic eventId of the i-th event of a batch: {@code demo-<batch>-<i>}. */
	static String eventId(String batch, int i) {
		return "demo-" + batch + "-" + i;
	}

	/**
	 * Base instant from which a batch's {@code occurredAt} values are spread backwards, one
	 * minute per event. A timestamp-shaped batch id is parsed as that instant (so the default
	 * run's base is current); any other batch id maps deterministically through its Java
	 * {@code String.hashCode} (specified stable across JVMs) into the 2026 reference window.
	 */
	static Instant baseInstant(String batch) {
		try {
			return LocalDateTime.parse(batch, DEFAULT_BATCH_FORMAT).toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException e) {
			long minutes = ((long) batch.hashCode() & 0x7fffffffL) % CUSTOM_BATCH_SPAN_MINUTES;
			return CUSTOM_BATCH_REFERENCE.plusSeconds(60L * minutes);
		}
	}

	/**
	 * {@code occurredAt} of the i-th event: the batch base instant minus i minutes, rendered
	 * as an ISO-8601 UTC instant the write API accepts (strict ISO-8601, within the supported
	 * 0001..9999 range).
	 */
	static String occurredAt(Instant base, int i) {
		return base.minusSeconds(60L * i).toString();
	}

	/**
	 * Hand-built JSON body of the i-th event of a batch. Plausible, small and fully derived
	 * from {@code (batch, i)} so every replay of the same batch is byte-identical (D4):
	 * one of four event types, one of three sources, one of five users and a tiny metadata
	 * object with an index-derived amount.
	 */
	static String payload(String batch, Instant base, int i) {
		String userId = "demo-user-" + (i % 5);
		String eventType = EVENT_TYPES[i % EVENT_TYPES.length];
		String source = SOURCES[i % SOURCES.length];
		String amount = (10 + (i % 50)) + "." + (i % 2 == 0 ? "00" : "50");
		return "{\"eventId\":\"" + jsonEscape(eventId(batch, i)) + "\""
				+ ",\"userId\":\"" + jsonEscape(userId) + "\""
				+ ",\"eventType\":\"" + eventType + "\""
				+ ",\"source\":\"" + source + "\""
				+ ",\"occurredAt\":\"" + occurredAt(base, i) + "\""
				+ ",\"metadata\":{\"amount\":" + amount + ",\"currency\":\"EUR\"}}";
	}

	/**
	 * Minimal JSON string escaping for the client-generated values: backslash, quote, the
	 * classic control characters and (defensively) remaining control characters below
	 * {@code 0x20} as {@code \\uXXXX}. The static value lists above need no escaping at all.
	 */
	static String jsonEscape(String value) {
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			switch (c) {
				case '\\' -> builder.append("\\\\");
				case '"' -> builder.append("\\\"");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20) {
						builder.append(String.format("\\u%04x", (int) c));
					}
					else {
						builder.append(c);
					}
				}
			}
		}
		return builder.toString();
	}
}
