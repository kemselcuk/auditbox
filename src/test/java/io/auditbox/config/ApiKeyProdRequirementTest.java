package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Focused context test for the prod fail-fast guard (T5.1, D18): in the {@code prod}
 * profile a missing, blank, or whitespace-only {@code auditbox.security.api-keys}, a
 * value that parses to zero entries, and a malformed entry all abort startup with a
 * message naming the property and the {@code API_KEYS} environment variable; a value with
 * at least one valid {@code source:sha256hex} entry starts the context. Runs without Boot
 * auto-configuration or a database — only the guard bean and the property binding are
 * under test.
 */
class ApiKeyProdRequirementTest {

	/** SHA-256 of a plaintext key — a valid digest for the success case. */
	private static final String DIGEST = HexFormat.of().formatHex(ApiKeyMatcher.sha256("prod-key"));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ApiKeyProdRequirement.class)
			// Activate the prod profile explicitly (ApplicationContextRunner has no
			// profile argument); the guard bean is then registered.
			.withInitializer(this::activateProdProfile);

	private void activateProdProfile(ConfigurableApplicationContext context) {
		context.getEnvironment().setActiveProfiles("prod");
	}

	@Test
	void prodStartupFailsWithoutApiKeys() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-keys");
			assertThat(context.getStartupFailure()).hasStackTraceContaining("API_KEYS");
		});
	}

	@Test
	void prodStartupFailsWithBlankApiKeys() {
		runner.withPropertyValues("auditbox.security.api-keys=")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-keys");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("API_KEYS");
				});
	}

	/**
	 * Review round 4, F3 (D5): a whitespace-only value would pass a plain {@code
	 * isBlank()} check on the environment string but must not start production either —
	 * it can never carry a valid key entry.
	 */
	@Test
	void prodStartupFailsWithWhitespaceOnlyApiKeys() {
		runner.withPropertyValues("auditbox.security.api-keys=   ")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-keys");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("API_KEYS");
				});
	}

	/**
	 * A non-blank value that parses to zero entries (here: only stray commas) is denied
	 * like a blank one: production must not start with a registry that authenticates
	 * nothing.
	 */
	@Test
	void prodStartupFailsWhenTheValueParsesToZeroEntries() {
		runner.withPropertyValues("auditbox.security.api-keys=,,,")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-keys");
					assertThat(context.getStartupFailure()).hasStackTraceContaining("API_KEYS");
				});
	}

	@Test
	void prodStartupFailsWithAMalformedEntryNamingTheProperty() {
		runner.withPropertyValues("auditbox.security.api-keys=app:not-a-hex-value")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-keys");
				});
	}

	@Test
	void prodStartupSucceedsWithAValidEntry() {
		runner.withPropertyValues("auditbox.security.api-keys=app:" + DIGEST)
				.run(context -> assertThat(context).hasNotFailed());
	}
}
