package io.auditbox.demo;

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

/**
 * Stage 6 demo-page security tests (plan task T6.2): the static single-page demo under
 * {@code /demo/**} is served without an API key — it carries no secrets and is the only
 * public surface besides {@code /actuator/health/**} — while the events API itself stays
 * key-protected for the page's visitors too (permitting the page never leaks event data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DemoStaticPageTest {

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

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void demoPageIsServedWithoutApiKeyAndContainsTheExpectedElements() throws Exception {
		HttpResponse<String> response = getWithoutKey("/demo/index.html");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
		// The key input, the load-more (cursor) button and the send-event form the README
		// page check relies on — plus the filters and the results table.
		assertThat(response.body())
				.contains("id=\"api-key\"")
				.contains("id=\"load-more\"")
				.contains("id=\"send-event\"")
				.contains("id=\"f-user-id\"")
				.contains("id=\"f-occurred-from\"")
				.contains("id=\"results\"");
	}

	@Test
	void demoPageCarriesNoSecrets() throws Exception {
		HttpResponse<String> response = getWithoutKey("/demo/index.html");

		// The page must not embed the configured key (test profile: test-api-key) or any
		// other credential: the real key value only ever arrives as visitor input.
		assertThat(response.body()).doesNotContain("test-api-key");
		assertThat(response.body()).doesNotContain("local-dev-key");
	}

	@Test
	void eventsApiStaysKeyProtectedWhileTheDemoPageIsPublic() throws Exception {
		assertThat(getWithoutKey("/demo/index.html").statusCode()).isEqualTo(200);
		assertThat(getWithoutKey("/api/v1/events").statusCode()).isEqualTo(401);
		assertThat(getWithoutKey("/api/v1/events?userId=demo-user-0").statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> getWithoutKey(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
