package io.auditbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import org.springframework.beans.factory.InitializingBean;

/**
 * Fails fast at startup in the {@code prod} profile (decisions D5 and D18, milestone
 * 10.1) when the API-key registry is not usable: {@code auditbox.security.api-keys},
 * bound from the {@code API_KEYS} environment variable, must be present, non-blank, and
 * parse to at least one {@code source:sha256hex} entry. Malformed entries already abort
 * startup in every profile ({@link ApiKeyRegistry}). The messages name the property and
 * the environment variable but never echo the configured value, a digest, or any other
 * key material. The check runs on
 * context refresh, before any request can be served, and aborts the application with a
 * {@link IllegalStateException}.
 *
 * <p>Outside {@code prod} no hard requirement exists: the {@code local}/{@code dev}
 * profiles default to the digest of the documented {@code local-dev-key}, the {@code test}
 * profile to the digest of {@code test-api-key}, and a profile-less run simply denies
 * every protected request (empty registry).
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
		String raw = environment.getProperty("auditbox.security.api-keys");
		if (raw == null || raw.isBlank()) {
			throw new IllegalStateException(
					"auditbox.security.api-keys is missing or blank: set the API_KEYS environment variable to "
							+ "comma-separated source:sha256hex entries "
							+ "(production startup requires at least one configured API key)");
		}
		if (ApiKeyRegistry.parse(raw).isEmpty()) {
			throw new IllegalStateException(
					"auditbox.security.api-keys contains no API key entries: set the API_KEYS environment variable to "
							+ "comma-separated source:sha256hex entries "
							+ "(production startup requires at least one configured API key)");
		}
	}
}
