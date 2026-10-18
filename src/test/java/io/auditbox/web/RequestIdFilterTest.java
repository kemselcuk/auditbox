package io.auditbox.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 9.5 over real HTTP: the {@link RequestIdFilter} correlation filter —
 *
 * <ul>
 *   <li>echoes a provided valid {@code X-Request-Id} verbatim,</li>
 *   <li>generates a UUID-shaped id when the header is absent,</li>
 *   <li>scrubs/replaces an unsafe (non-matching) id instead of echoing it,</li>
 *   <li>also echoes the id on the 401 response of a keyless request (the filter runs
 *       ahead of the security chain) — the API still requires the key.</li>
 * </ul>
 *
 * <p>The decision tests of the static {@code requestIdFor} method need no web stack and
 * give the safe-pattern boundary (length 1, 128, 129) exact coverage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RequestIdFilterTest {

	private static final String API_KEY = "test-api-key";

	private static final String EVENT_PATH = "/api/v1/events";

	/** A UUID-shaped generated id (the only generation form; the pattern is not strict). */
	private static final Pattern GENERATED = Pattern.compile(
			"^[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}$");

	/** The exact accepted shape the filter echoes verbatim (same pattern as the filter). */
	private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newHttpClient();

	// ---------------------------------------------------------------------
	// Decision logic (no web stack needed)
	// ---------------------------------------------------------------------

	@Test
	void decisionAcceptsExactlyTheSafePatternBoundaries() {
		assertThat(RequestIdFilter.requestIdFor("m9.5.ok_id-1")).isEqualTo("m9.5.ok_id-1");
		assertThat(RequestIdFilter.requestIdFor("a")).isEqualTo("a");
		assertThat(RequestIdFilter.requestIdFor("r".repeat(128))).isEqualTo("r".repeat(128));

		// Anything outside the pattern is replaced by a generated id:
		assertThat(RequestIdFilter.requestIdFor(null)).matches(GENERATED);
		assertThat(RequestIdFilter.requestIdFor("")).matches(GENERATED);
		// Length 129 is beyond the bound:
		assertThat(RequestIdFilter.requestIdFor("r".repeat(129))).matches(GENERATED);
		// Spaces and characters outside the safe alphabet:
		assertThat(RequestIdFilter.requestIdFor("unsafe|pipe!+")).matches(GENERATED);
	}

	// ---------------------------------------------------------------------
	// End-to-end behavior on the real HTTP stack
	// ---------------------------------------------------------------------

	@Test
	void validIncomingIdIsEchoedVerbatim() throws Exception {
		HttpResponse<String> response = getWithKey(EVENT_PATH, "m9.5-echo-me_1");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue(RequestIdFilter.HEADER).orElse("missing-header"))
				.isEqualTo("m9.5-echo-me_1");
	}

	@Test
	void idIsGeneratedWhenTheHeaderIsAbsent() throws Exception {
		HttpResponse<String> response = getWithKey(EVENT_PATH, null);

		assertThat(response.statusCode()).isEqualTo(200);
		String emitted = response.headers().firstValue(RequestIdFilter.HEADER).orElse(null);
		assertThat(emitted).isNotNull().matches(GENERATED);
	}

	@Test
	void unsafeIncomingIdIsReplacedByAGeneratedOneAndNeverEchoed() throws Exception {
		String unsafe = "probe header(plain)!!";
		assertThat(unsafe).matches(value -> !SAFE.matcher(value).matches());

		HttpResponse<String> response = getWithKey(EVENT_PATH, unsafe);

		assertThat(response.statusCode()).isEqualTo(200);
		String emitted = response.headers().firstValue(RequestIdFilter.HEADER).orElse(null);
		assertThat(emitted).isNotNull().matches(GENERATED).isNotEqualTo(unsafe);
	}

	@Test
	void tooLongIncomingIdIsReplacedByAGeneratedOne() throws Exception {
		String tooLong = "a".repeat(129);

		HttpResponse<String> response = getWithKey(EVENT_PATH + "?x=1", tooLong);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue(RequestIdFilter.HEADER).orElse(null))
				.isNotNull().matches(GENERATED).isNotEqualTo(tooLong);
	}

	@Test
	void theEchoedGeneratedIdMatchesTheSafePattern() throws Exception {
		HttpResponse<String> response = getWithKey(EVENT_PATH, null);

		assertThat(response.headers().firstValue(RequestIdFilter.HEADER).orElse(null))
				.matches(String.valueOf(SAFE));
	}

	@Test
	void theApiStillRequiresTheKeyAndTheIdIsEchoedOnThe401() throws Exception {
		HttpResponse<String> response = get(EVENT_PATH, "m9.5-on-401", null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue(RequestIdFilter.HEADER).orElse("missing-header"))
				.isEqualTo("m9.5-on-401");
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private HttpResponse<String> get(String path, String requestId, String apiKey) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.GET();
		if (requestId != null) {
			builder.header(RequestIdFilter.HEADER, requestId);
		}
		if (apiKey != null) {
			builder.header(RequestIdFilterTest.headerName(), apiKey);
		}
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** The filter is key-agnostic; the constant-time-key check is the security chain's job. */
	private HttpResponse<String> getWithKey(String path, String requestId) throws Exception {
		return get(path, requestId, API_KEY);
	}

	private static String headerName() {
		return "X-API-Key";
	}
}
