package io.auditbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import io.auditbox.metrics.AuditMetrics;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.DispatcherType;

/**
 * Real, stateless {@code X-API-Key} security (decision D5, plan task T5.1) — replaces the
 * Stage 3 temporary basic-auth chain, which was deleted together with the
 * {@code spring.security.user.*} test credentials. Exactly one {@link SecurityFilterChain}
 * remains, active in every profile:
 *
 * <ul>
 *   <li>no CSRF (token-free API), no sessions ({@link SessionCreationPolicy#STATELESS}),
 *       no form login, no HTTP basic — the only authentication mechanism is the
 *       {@link ApiKeyAuthenticationFilter},</li>
 *   <li>{@code /actuator/health/**} (health + nested liveness/readiness probes) and the static
 *       single-page demo under {@code /demo/**} (plan task T6.2) are public. The demo page is
 *       vanilla HTML and carries no secrets: it lets a human paste their {@code X-API-Key}
 *       value into a form, but every API call it makes goes through this same filter chain —
 *       the events API itself stays key-protected for the page's visitors too,</li>
 *   <li>everything else — the events API, every other actuator endpoint, and, when enabled,
 *       {@code /v3/api-docs} and {@code /swagger-ui/**} — requires the key,</li>
 *   <li>missing or wrong key &rarr; 401 Problem Details via
 *       {@link ApiKeyAuthenticationEntryPoint},</li>
 *   <li>no logout: there is nothing to log out, and the default {@code LogoutFilter} would
 *       otherwise answer {@code POST/GET /logout} without a key with a 302 redirect to
 *       {@code /login?logout} instead of requiring the key (review round 4, F2),</li>
 *   <li>{@link DispatcherType#ERROR} dispatches are permitted so an authenticated request to
 *       an unmapped path or with an unsupported method still renders its 404/405 instead of
 *       being turned into a 401 by the error page; the original request still had to pass the
 *       key check (review of Stage 5).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeyProperties.class)
public class ApiKeySecurityConfiguration {

	@Bean
	SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http, ApiKeyProperties properties,
			AuditMetrics metrics) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						// Static single-page demo only — never the API (plan task T6.2).
						.requestMatchers("/demo/**").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(new ApiKeyAuthenticationFilter(properties),
						UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint(metrics)))
				.build();
	}
}
