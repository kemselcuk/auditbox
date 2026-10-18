package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 5 security tests (plan task T5.1, decision D5) over real HTTP: the static
 * {@code X-API-Key} filter chain
 *
 * <ul>
 *   <li>rejects a missing key with 401 Problem Details ({@code application/problem+json},
 *       title "Unauthorized", detail naming the header, {@code WWW-Authenticate: X-API-Key}),</li>
 *   <li>rejects a wrong key with the identical 401 without echoing the submitted key,</li>
 *   <li>accepts the correct key for write (201) and read (200), including under a lowercase
 *       header name,</li>
 *   <li>keeps {@code /actuator/health} public and every other actuator path protected,</li>
 *   <li>requires the key for {@code /logout} too — logout is disabled (review round 4, F2),</li>
 *   <li>keeps {@code /v3/api-docs} (enabled in the test profile) protected,</li>
 *   <li>declares the apiKey SecurityScheme (header X-API-Key) in the OpenAPI document.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ApiKeySecurityTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String API_KEY = "test-api-key";

	/**
	 * Pinned PostgreSQL image; keep in sync with docker-compose.yml.
	 * Connection details are injected into Spring via {@code @ServiceConnection}.
	 */
	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private final HttpClient client = HttpClient.newHttpClient();

	// ---------------------------------------------------------------------
	// D5/T5.1 acceptance: 401 / 401 / 201 pattern
	// ---------------------------------------------------------------------

	@Test
	void missingApiKeyIsRejectedWithProblemDetails401() throws Exception {
		HttpResponse<String> response = getWithoutKey("/api/v1/events");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		// The challenge names the required header (no credential material).
		assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).isEqualTo("X-API-Key");
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Unauthorized");
		assertThat(problem.get("status").intValue()).isEqualTo(401);
		assertThat(problem.get("detail").stringValue()).contains("X-API-Key");
	}

	@Test
	void wrongApiKeyIsRejectedWith401WithoutEchoingTheSubmittedKey() throws Exception {
		String wrongKey = "definitely-not-the-key";
		// A wrong-key POST attempt for its would-be user: the request would have written an
		// event for userId "t5-nobody", so the following row count proves nothing leaked
		// through (the earlier GET-only variant of this assertion was vacuous).
		HttpRequest attempt = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header("Content-Type", "application/json")
				.header(ApiKeyAuthenticationFilter.HEADER, wrongKey)
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"eventId\":\"t5-nobody-should-not-exist\",\"userId\":\"t5-nobody\","
								+ "\"eventType\":\"payment.settled\",\"source\":\"security-test\","
								+ "\"occurredAt\":\"2026-10-18T12:00:00Z\"}", StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = client.send(attempt, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		assertThat(JSON.readTree(response.body()).get("title").stringValue()).isEqualTo("Unauthorized");
		// The 401 body must never echo the submitted (wrong) key.
		assertThat(response.body()).doesNotContain(wrongKey);
		// And nothing was written for the would-be user.
		assertThat(eventRowCount("t5-nobody")).isEqualTo(0);
	}

	/**
	 * Header names are case-insensitive per RFC 9110: a lowercase {@code x-api-key} must
	 * authenticate exactly like {@code X-API-Key} (review round 4 nit).
	 */
	@Test
	void lowerCaseHeaderNameAuthenticatesTheSameWay() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header("x-api-key", API_KEY)
				.GET()
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
	}

	@Test
	void correctApiKeyAllowsWriteAndRead() throws Exception {
		String eventId = "t5-" + UUID.randomUUID();

		HttpResponse<String> written = postWithKey("{\"eventId\":\"" + eventId + "\",\"userId\":\"t5-user\","
				+ "\"eventType\":\"payment.settled\",\"source\":\"security-test\","
				+ "\"occurredAt\":\"2026-10-18T12:00:00Z\"}");

		assertThat(written.statusCode()).isEqualTo(201);
		assertThat(JSON.readTree(written.body()).get("eventId").stringValue()).isEqualTo(eventId);

		HttpResponse<String> listed = getWithKey("/api/v1/events?userId=t5-user", API_KEY);
		assertThat(listed.statusCode()).isEqualTo(200);
		assertThat(listed.body()).contains(eventId);
	}

	// ---------------------------------------------------------------------
	// Public surface: health probes and everything else
	// ---------------------------------------------------------------------

	@Test
	void actuatorHealthIsPublicWithoutApiKey() throws Exception {
		HttpResponse<String> response = getWithoutKey("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void otherActuatorPathRequiresApiKey() throws Exception {
		HttpResponse<String> response = getWithoutKey("/actuator/info");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
	}

	@Test
	void apiDocsWithoutApiKeyIsRejectedWhileEnabledInTest() throws Exception {
		HttpResponse<String> response = getWithoutKey("/v3/api-docs");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(JSON.readTree(response.body()).get("detail").stringValue()).contains("X-API-Key");
	}

	@Test
	void authenticatedRequestToUnknownPathReturns404NotAuthError() throws Exception {
		// Review of Stage 5: the ERROR dispatch must not turn an authenticated 404 into a 401.
		HttpResponse<String> response = getWithKey("/does-not-exist", API_KEY);

		assertThat(response.statusCode()).isEqualTo(404);
	}

	@Test
	void authenticatedUnsupportedMethodReturns405NotAuthError() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header(ApiKeyAuthenticationFilter.HEADER, API_KEY)
				.method("PUT", HttpRequest.BodyPublishers.ofString("{}"))
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(405);
	}

	@Test
	void unknownPathWithoutKeyIsStillRejected() throws Exception {
		HttpResponse<String> response = getWithoutKey("/does-not-exist");

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void duplicateApiKeyHeadersUseTheFirstValue() throws Exception {
		// Exactly one X-API-Key header is expected; when a proxy emits the header twice the
		// servlet request exposes the first value to the filter (README documents this,
		// review round 6, F3).
		HttpResponse<String> wrongFirst = getWithTwoKeys("wrong-first", API_KEY);
		assertThat(wrongFirst.statusCode()).isEqualTo(401);

		HttpResponse<String> correctFirst = getWithTwoKeys(API_KEY, "wrong-second");
		assertThat(correctFirst.statusCode()).isEqualTo(200);
	}

	/**
	 * Review round 4, F2: logout is disabled, so {@code /logout} without a key is answered
	 * by the entry point (401) and not by the default {@code LogoutFilter}, which would 302
	 * to {@code /login?logout} as if logout were a permitted path.
	 */
	@Test
	void logoutWithoutApiKeyReturns401Not302() throws Exception {
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/logout"))
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
	}

	// ---------------------------------------------------------------------
	// OpenAPI security scheme
	// ---------------------------------------------------------------------

	@Test
	void openApiDocumentDeclaresApiKeySecurityScheme() throws Exception {
		HttpResponse<String> response = getWithKey("/v3/api-docs", API_KEY);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode docs = JSON.readTree(response.body());

		JsonNode scheme = docs.get("components").get("securitySchemes").get("ApiKeyAuth");
		assertThat(scheme.get("type").stringValue()).isEqualTo("apiKey");
		assertThat(scheme.get("in").stringValue()).isEqualTo("header");
		assertThat(scheme.get("name").stringValue()).isEqualTo("X-API-Key");
		// Global security requirement referencing the scheme.
		assertThat(docs.get("security").get(0).get("ApiKeyAuth")).isNotNull();
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private HttpResponse<String> getWithoutKey(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getWithKey(String pathAndQuery, String key) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
				.header(ApiKeyAuthenticationFilter.HEADER, key)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getWithTwoKeys(String first, String second) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header(ApiKeyAuthenticationFilter.HEADER, first)
				.header(ApiKeyAuthenticationFilter.HEADER, second)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postWithKey(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header("Content-Type", "application/json")
				.header(ApiKeyAuthenticationFilter.HEADER, API_KEY)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private int eventRowCount(String userId) {
		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM events WHERE user_id = ?",
				Integer.class, userId);
		return count == null ? 0 : count;
	}
}
