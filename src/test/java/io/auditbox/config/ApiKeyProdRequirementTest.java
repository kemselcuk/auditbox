package io.auditbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Focused context test for the prod fail-fast guard (T5.1, D5): in the {@code prod}
 * profile a missing or blank {@code auditbox.security.api-key} aborts startup, a
 * configured key starts the context. Runs without Boot auto-configuration or a database —
 * only the guard bean and the property binding are under test.
 */
class ApiKeyProdRequirementTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ApiKeyProdRequirement.class)
			// Activate the prod profile explicitly ( ApplicationContextRunner has no
			// profile argument); the guard bean is then registered.
			.withInitializer(this::activateProdProfile);

	private void activateProdProfile(ConfigurableApplicationContext context) {
		context.getEnvironment().setActiveProfiles("prod");
	}

	@Test
	void prodStartupFailsWithoutApiKey() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-key");
		});
	}

	@Test
	void prodStartupFailsWithBlankApiKey() {
		runner.withPropertyValues("auditbox.security.api-key=")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-key");
				});
	}

	/**
	 * Review round 4, F3: a whitespace-only key would pass a plain {@code isBlank()} check
	 * but can never match a header value once the configured side is stripped — it must
	 * abort startup exactly like missing/blank.
	 */
	@Test
	void prodStartupFailsWithWhitespaceOnlyApiKey() {
		runner.withPropertyValues("auditbox.security.api-key=   ")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("auditbox.security.api-key");
				});
	}

	@Test
	void prodStartupSucceedsWithConfiguredApiKey() {
		runner.withPropertyValues("auditbox.security.api-key=secure-prod-key")
				.run(context -> assertThat(context).hasNotFailed());
	}
}
