package io.auditbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * <strong>Temporary</strong> security configuration for Stage 3 (plan tasks T3.1/T3.2) —
 * Stage 5 replaces this class with the real stateless X-API-Key filter chain (D5).
 *
 * <p>The events API must be callable over plain HTTP POST for the Stage 3 tests, which the
 * auto-configured default chain does not allow (it enables CSRF and form login for browser
 * flows). This chain keeps an explicit authentication requirement (missing or wrong
 * credentials &rarr; 401 with HTTP Basic), disables CSRF for the stateless API, keeps sessions
 * off, and leaves the actuator health endpoint public for the compose healthcheck. Tests
 * authenticate with the static basic-auth credentials configured in {@code application-test.yml}
 * ({@code spring.security.user.name/password}); those credentials are test-only and are
 * replaced together with this mechanism in Stage 5. The chain is active only in the
 * {@code test} and {@code local} profiles: anything else (including a production-like
 * profile-less run) keeps Boot's default security until Stage 5 ships the real X-API-Key
 * filter chain.
 */
@Configuration
@EnableWebSecurity
@Profile({ "test", "local" })
public class TemporarySecurityConfiguration {

	@Bean
	SecurityFilterChain temporaryEventsSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/actuator/health", "/actuator/info").permitAll()
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
	}
}
