package io.auditbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static API-key security properties (decision D5, plan task T5.1). The single property
 * {@code auditbox.security.api-key} is bound from the {@code API_KEY} environment variable:
 *
 * <ul>
 *   <li>base configuration: {@code ${API_KEY:}} — blank by default (deny-all behavior of the
 *       filter: a blank configured key never matches any header value),</li>
 *   <li>{@code local}/{@code dev}: safe development default {@code local-dev-key},</li>
 *   <li>{@code test}: {@code test-api-key},</li>
 *   <li>{@code prod}: {@link ApiKeyProdRequirement} fails startup when the key is missing or
 *       blank.</li>
 * </ul>
 *
 * <p>The value is never logged and never exposed; {@link ApiKeyMatcher} hashes it for
 * constant-time comparison.
 */
@ConfigurationProperties(prefix = "auditbox.security")
public record ApiKeyProperties(String apiKey) {
}
