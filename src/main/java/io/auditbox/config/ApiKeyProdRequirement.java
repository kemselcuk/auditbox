package io.auditbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import org.springframework.beans.factory.InitializingBean;

/**
 * Fails fast at startup in the {@code prod} profile (decision D5, plan task T5.1) when the
 * static API key is not configured: {@code auditbox.security.api-key}, bound from the
 * {@code API_KEY} environment variable, must be present and non-blank. The check runs on
 * context refresh, before any request can be served, and aborts the application with a
 * {@link IllegalStateException}.
 *
 * <p>Outside {@code prod} no hard requirement exists: the {@code local}/{@code dev}
 * profiles have the safe {@code local-dev-key} default, the {@code test} profile has its
 * own key, and a profile-less run simply denies every protected request (a blank configured
 * key never matches, see {@link ApiKeyMatcher}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ApiKeyProdRequirement implements InitializingBean {

	private final Environment environment;

	ApiKeyProdRequirement(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		String key = environment.getProperty("auditbox.security.api-key");
		if (key == null || key.isBlank()) {
			throw new IllegalStateException(
					"auditbox.security.api-key is missing or blank: set the API_KEY environment variable "
							+ "(production startup requires a non-blank API key)");
		}
	}
}
