package io.auditbox.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 9.7: Playwright end-to-end tests for the static demo page
 * ({@code /demo/index.html}, public per D15), run behind the opt-in {@code e2e}
 * Maven profile only. The class boots the real application (same
 * {@code @SpringBootTest(RANDOM_PORT)} + Testcontainers PostgreSQL pattern as the
 * integration tests) and drives a real Chromium against it.
 *
 * <p>Determinism rules (M9.7 acceptance criteria):
 * <ul>
 *   <li>no {@code Thread.sleep} — every wait is a Playwright locator or an explicit
 *       Playwright {@code expect} condition,</li>
 *   <li>each test runs on its own fresh browser context and filters by a unique
 *       {@code userId}, so the shared events table cannot leak state between tests,</li>
 *   <li>the API key value is only typed into the demo page's own input field; the tests
 *       never assert on, or log, the key itself.</li>
 * </ul>
 *
 * <p>Failures record a Playwright trace (screenshots + snapshots) under
 * {@code target/playwright-traces/}; successful tests delete their trace so that only
 * failing runs produce uploadable artefacts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DemoPageE2ETest {

	private static final String API_KEY = "test-api-key";

	private static final String EVENT_PATH = "/api/v1/events";

	@SuppressWarnings("resource")
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
			.withDatabaseName("auditbox");

	@LocalServerPort
	int port;

	private static Playwright playwright;
	private static Browser browser;

	private BrowserContext context;
	private Page page;
	private Path tracePath;

	@BeforeAll
	static void startChromium() throws Exception {
		// First run for a user/CI runner downloads Chromium (~300 MB), then it is a
		// no-op against the local cache. It runs as an external process because
		// Playwright's CLI.main itself calls System.exit, which would kill this
		// (forked Surefire) JVM. The e2e workflow sets PLAYWRIGHT_INSTALL_WITH_DEPS
		// so the Linux system packages are installed too (review round 2, finding 3).
		List<String> command = new ArrayList<>(List.of(javaExecutable(), "-cp",
				System.getProperty("java.class.path"), "com.microsoft.playwright.CLI", "install", "chromium"));
		if (System.getenv("PLAYWRIGHT_INSTALL_WITH_DEPS") != null) {
			command.add("--with-deps");
		}
		Process install = new ProcessBuilder(command).inheritIO().start();
		int exitCode = install.waitFor();
		if (exitCode != 0) {
			// Fail fast with the real cause instead of a later "executable does not exist".
			throw new IllegalStateException("Playwright Chromium install failed with exit code " + exitCode);
		}
		playwright = Playwright.create();
		browser = playwright.chromium().launch();
	}

	/** The JVM that runs the tests, so the classpath always matches (review round 2, finding 7). */
	private static String javaExecutable() {
		String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
		return Path.of(System.getProperty("java.home"), "bin", executable).toString();
	}

	@AfterAll
	static void stopChromium() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void openFreshPage(TestInfo testInfo) {
		String baseName = DemoPageE2ETest.class.getSimpleName() + "-"
				+ testInfo.getTestMethod().orElseThrow().getName();
		tracePath = Path.of("target", "playwright-traces", baseName + ".zip");
		context = browser.newContext();
		// Trace for the failure-artefact upload; stopTrace deletes it on success.
		context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
		page = context.newPage();
		page.navigate(demoUrl());
	}

	@AfterEach
	void closeContext() {
		// The trace was already finished by run(); closing the context discards any
		// trace left open by a failed @BeforeEach.
		if (context != null) {
			context.close();
		}
	}

	@FunctionalInterface
	private interface TestBody {
		void run() throws Exception;
	}

	/**
	 * Runs a test body and finishes its Playwright trace: deleted when the body passes,
	 * kept under {@code target/playwright-traces} when it fails. No sleeps anywhere.
	 */
	private void run(TestBody body) throws Exception {
		boolean failed = false;
		try {
			body.run();
		}
		catch (Throwable t) {
			failed = true;
			if (t instanceof Error error) {
				throw error;
			}
			if (t instanceof Exception exception) {
				throw exception;
			}
			throw new IllegalStateException(t);
		}
		finally {
			stopTrace(failed);
		}
	}

	private void stopTrace(boolean keep) throws Exception {
		try {
			context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
			if (!keep) {
				Files.deleteIfExists(tracePath);
			}
		}
		catch (Exception alreadyStoppedOrUnwritable) {
			// Trace handling must never mask the test outcome (review round 2, finding 6).
		}
	}

	@Test
	void demoPageLoadsAndShowsItsInitialState() throws Exception {
		run(() -> {
			PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("AuditBox demo"))).isVisible();
			PlaywrightAssertions.assertThat(page.locator("#api-key")).isEditable();
			PlaywrightAssertions.assertThat(page.locator("#load-events")).isVisible();
			// No walk started yet: "Load more" is disabled and the results body is empty.
			PlaywrightAssertions.assertThat(page.locator("#load-more")).isDisabled();
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(0);
			// The demo page is static and carries no secrets (D5): the key field is empty.
			PlaywrightAssertions.assertThat(page.locator("#api-key")).hasValue("");
		});
	}

	@Test
	void withAValidKeyTheDemoPageListsSeededEvents() throws Exception {
		run(() -> {
			String userId = uniqueSuffix("m9e7-list");
			List<String> eventIds = seedEvents(userId, 3);

			fillKeyAndUserFilter(userId);
			page.locator("#load-events").click();

			PlaywrightAssertions.assertThat(page.locator("#list-status")).containsText("3 event(s)");
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(3);
			// The seeded eventIds really rendered in the table (row 1 = newest seed:
			// the API lists occurred_at DESC, and seed i=0 carries the latest stamp).
			assertThat(page.locator("#rows tr td:nth-child(2)").allTextContents())
					.containsExactlyElementsOf(eventIds);
		});
	}

	@Test
	void loadMoreWalksTheCursorAndAppendsWithNoDuplicates() throws Exception {
		run(() -> {
			String userId = uniqueSuffix("m9e7-walk");
			List<String> eventIds = seedEvents(userId, 5);

			fillKeyAndUserFilter(userId);
			page.locator("#f-size").fill("2");
			page.locator("#load-events").click();

			// Page 1: exactly the configured page size, and a cursor is available.
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(2);
			PlaywrightAssertions.assertThat(page.locator("#list-status")).containsText("2 event(s)");
			PlaywrightAssertions.assertThat(page.locator("#list-status")).containsText("nextCursor available");
			PlaywrightAssertions.assertThat(page.locator("#load-more")).isEnabled();

			page.locator("#load-more").click();
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(4);

			page.locator("#load-more").click();
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(5);
			// Last page: page size 2 does not divide 5, so the final page ends the walk.
			PlaywrightAssertions.assertThat(page.locator("#load-more")).isDisabled();

			// The three appended pages together carry every seeded eventId exactly once.
			List<String> rendered = page.locator("#rows tr td:nth-child(2)").allTextContents();
			assertThat(rendered).containsExactlyInAnyOrderElementsOf(eventIds);
		});
	}

	@Test
	void anEventTypeFilterNarrowsTheList() throws Exception {
		run(() -> {
			String userId = uniqueSuffix("m9e7-filter");
			List<String> eventIds = seedEvents(userId, 2);

			fillKeyAndUserFilter(userId);
			page.locator("#f-event-type").fill("payment.settled");
			page.locator("#load-events").click();

			// seedEvents seeds event 0 as payment.settled and event 1 as invoice.settled.
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(1);
			assertThat(page.locator("#rows tr td:nth-child(2)").allTextContents()).containsExactly(eventIds.get(0));
		});
	}

	@Test
	void aWrongKeySurfacesTheUnauthorizedProblemDetails() throws Exception {
		run(() -> {
			page.locator("#api-key").fill("not-the-real-key-" + UUID.randomUUID());
			page.locator("#load-events").click();

			// The page renders RFC 9457 Problem Details in its error box; the 401 body
			// from ApiKeyAuthenticationEntryPoint carries title "Unauthorized" and a
			// detail naming the X-API-Key header. Never asserted: the key value itself.
			PlaywrightAssertions.assertThat(page.locator("#error-box")).isVisible();
			PlaywrightAssertions.assertThat(page.locator("#error-title")).containsText("Unauthorized");
			PlaywrightAssertions.assertThat(page.locator("#error-status")).containsText("401");
			PlaywrightAssertions.assertThat(page.locator("#error-detail")).containsText("X-API-Key");
			// No rows were rendered from a failed request.
			PlaywrightAssertions.assertThat(page.locator("#rows tr")).hasCount(0);
		});
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private String demoUrl() {
		return "http://localhost:" + port + "/demo/index.html";
	}

	private static String uniqueSuffix(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private void fillKeyAndUserFilter(String userId) {
		// The key goes only into the demo page's own input field, never into logs.
		page.locator("#api-key").fill(API_KEY);
		page.locator("#f-user-id").fill(userId);
	}

	/** Posts {@code count} events via HTTP with the test key; deterministic per run. */
	private List<String> seedEvents(String userId, int count) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		List<String> eventIds = new ArrayList<>();
		// One base instant for the whole batch: the stamps are strictly one second apart,
		// so the server's keyset order (occurred_at DESC) matches eventIds exactly.
		// Evaluating now() per iteration let two adjacent stamps collide in the same
		// truncated second, which made the exact-order assertion flaky (review round 2,
		// finding 1).
		Instant base = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		for (int i = 0; i < count; i++) {
			String eventId = uniqueSuffix("m9e7-event");
			String eventType = (i == 0) ? "payment.settled" : "invoice.settled";
			String occurredAt = base.minusSeconds(i).toString();
			String metadata = (i == 0) ? ",\"metadata\":{\"amount\":" + (i + 1) + "}" : "";
			String body = "{\"eventId\":\"" + eventId + "\",\"userId\":\"" + userId + "\",\"eventType\":\""
					+ eventType + "\",\"source\":\"m9e7-e2e\",\"occurredAt\":\"" + occurredAt + "\""
					+ metadata + "}";
			HttpRequest request = HttpRequest
					.newBuilder(URI.create("http://localhost:" + port + EVENT_PATH))
					.header("Content-Type", "application/json")
					.header("X-API-Key", API_KEY)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 201) {
				throw new IllegalStateException("Seeding event " + eventId + " returned HTTP "
						+ response.statusCode() + ": " + response.body());
			}
			eventIds.add(eventId);
		}
		return eventIds;
	}
}
