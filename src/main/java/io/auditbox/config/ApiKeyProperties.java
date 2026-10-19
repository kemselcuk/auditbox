package io.auditbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API-key registry properties (decisions D5 and D18, milestone 10.1). The raw
 * {@code auditbox.security.api-keys} string is bound from the {@code API_KEYS} environment
 * variable as comma-separated {@code source:sha256hex} entries, where {@code sha256hex} is
 * the SHA-256 hex digest of the plaintext key — the plaintext keys are never stored:
 *
 * <ul>
 *   <li>base configuration: {@code ${API_KEYS:}} — blank by default, yielding an empty
 *       registry (deny-all),</li>
 *   <li>{@code local}/{@code dev}: the documented development key {@code local-dev-key} via
 *       its digest,</li>
 *   <li>{@code test}: {@code test-api-key} via its digest,</li>
 *   <li>{@code prod}: {@link ApiKeyProdRequirement} fails startup when the value is
 *       missing, blank, or parses to zero entries.</li>
 * </ul>
 *
 * <p>The raw value is parsed and validated exactly once at construction (see
 * {@link ApiKeyRegistry}); a malformed entry aborts startup in every profile. No value is
 * ever logged or exposed.
 */
@ConfigurationProperties(prefix = "auditbox.security")
public record ApiKeyProperties(String apiKeys) {
}
