package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.auditbox.metrics.AuditMetrics;

/**
 * Milestone 10.2 / decision D19: real-HTTP behavior of the per-source rate limiter.
 * Scenario (single deterministic test method inside one context — the buckets live for
 * the whole context, so one method guarantees the exact request sequence): the configured
 * limit is 2 requests per minute, two sources are configured, and
 *
 * <ul>
 *   <li>two authenticated requests from source {@code rate-a} pass,</li>
 *   <li>the third gets 429 with {@code Retry-After} ≥ 1 and a
 *       {@code application/problem+json} body that names no key material or source,</li>
 *   <li>exactly one {@code auditbox.rate.limited} increment per 429 (delta-based, like
 *       {@code MetricsApiControllerTest}),</li>
 *   <li>source {@code rate-b} still passes from its own untouched bucket,</li>
 *   <li>a key-less request still gets 401 (never 429) and is not counted,</li>
 *   <li>{@code /actuator/health} stays public and unlimited (200).</li>
 * </ul>
 *
 * <p>Configuration via test properties instead of {@code application-test.yml}: overriding
 * {@code auditbox.security.api-keys} with two sources and lowering the limit. The digests
 * below are the SHA-256 digests of the plaintext test keys {@code rate-key-a} and
 * {@code rate-key-b} (plain strings pinned in this file; no test secret is involved).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"auditbox.security.api-keys=rate-a:b43b225f8224164d9513d2dcd0e9f7314782e98571de994cfad9cd6dc94c781c,"
				+ "rate-b:cf1305599459d265456037de0dd40d6573642f13f5aaf4de36fc2961835a0c72",
		"auditbox.rate-limit.requests-per-minute=2"
})
@Testcontainers
@ActiveProfiles("test")
class RateLimitApiIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String KEY_A = "rate-key-a";

	private static final String KEY_B = "rate-key-b";

	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	@Autowired
	private MeterRegistry meterRegistry;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void authenticatedRequestsAreLimitedPerSourceAndUnauthenticatedOnesKeepTheir401() throws Exception {
		double limitedBefore = count(AuditMetrics.RATE_LIMITED);

		HttpResponse<String> first = getWithKey(KEY_A);
		HttpResponse<String> second = getWithKey(KEY_A);
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(count(AuditMetrics.RATE_LIMITED)).isEqualTo(limitedBefore);

		HttpResponse<String> third = getWithKey(KEY_A);
		assertThat(third.statusCode()).isEqualTo(429);
		assertThat(third.headers().firstValue("Content-Type").orElse(""))
				.startsWith("application/problem+json");
		assertThat(third.headers().firstValue(RateLimitFilter.RETRY_AFTER_HEADER).orElse(""))
				.matches("[0-9]+");
		int retryAfter = Integer.parseInt(third.headers().firstValue(RateLimitFilter.RETRY_AFTER_HEADER).orElse("0"));
		assertThat(retryAfter).isGreaterThanOrEqualTo(1);
		tools.jackson.databind.JsonNode problem = JSON.readTree(third.body());
		assertThat(problem.get("title").stringValue()).isEqualTo("Too Many Requests");
		assertThat(problem.get("status").intValue()).isEqualTo(429);
		// No key material and no source name ever reaches the body.
		assertThat(third.body()).doesNotContain(KEY_A).doesNotContain("rate-a");

		assertThat(count(AuditMetrics.RATE_LIMITED)).isEqualTo(limitedBefore + 1); // exactly one per 429

		// The OTHER source still passes from its own bucket.
		HttpResponse<String> otherSource = getWithKey(KEY_B);
		assertThat(otherSource.statusCode()).isEqualTo(200);
		assertThat(count(AuditMetrics.RATE_LIMITED)).isEqualTo(limitedBefore + 1);

		// A key-less request keeps its 401 and consumes no token, so no 429 and no count.
		HttpResponse<String> withoutKey = getWithoutKey("/api/v1/events");
		assertThat(withoutKey.statusCode()).isEqualTo(401);
		assertThat(count(AuditMetrics.RATE_LIMITED)).isEqualTo(limitedBefore + 1);

		// Health stays public and unlimited even after both known limits interacted above.
		HttpResponse<String> health = getWithoutKey("/actuator/health");
		assertThat(health.statusCode()).isEqualTo(200);
	}

	private double count(String name) {
		Counter counter = meterRegistry.find(name).counter();
		return counter == null ? 0.0 : counter.count();
	}

	private HttpResponse<String> getWithKey(String key) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
				.header("X-API-Key", key)
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getWithoutKey(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
