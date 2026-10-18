package io.auditbox.config;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.auditbox.metrics.AuditMetrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless {@code X-API-Key} authentication filter (decision D5, plan task T5.1):
 *
 * <ul>
 *   <li>reads the {@code X-API-Key} header once per request and compares it against the
 *       configured key with {@link ApiKeyMatcher} (constant time),</li>
 *   <li>on success puts an authenticated {@link Authentication} into the
 *       {@link SecurityContext} (principal is a static client name — the key itself is never
 *       stored in the context, log, or response) and continues the chain,</li>
 *   <li>on failure continues the chain unauthenticated; the authorization rules
 *       ({@code anyRequest().authenticated()}) then raise the 401 (rendered as Problem
 *       Details by {@link ApiKeyAuthenticationEntryPoint}). The filter deliberately does not
 *       send the 401 itself so missing and wrong keys take the identical code path.</li>
 * </ul>
 *
 * <p>The context is stateless per request: with {@code SessionCreationPolicy.STATELESS} and
 * no {@code SecurityContextRepository}, nothing is persisted to a session or cookie.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	/** Header carrying the static API key; the OpenAPI SecurityScheme documents the same name. */
	static final String HEADER = "X-API-Key";

	private final ApiKeyProperties properties;

	private final AuditMetrics metrics;

	ApiKeyAuthenticationFilter(ApiKeyProperties properties, AuditMetrics metrics) {
		this.properties = properties;
		this.metrics = metrics;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String provided = request.getHeader(HEADER);
		// Never log the header or its value.
		if (ApiKeyMatcher.matches(properties.apiKey(), provided)) {
			Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
					"api-key-client", null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
		}
		else if (!PublicPaths.matches(request.getRequestURI())) {
			// Milestone 9.4: failed key checks on key-protected paths count — reason=missing
			// when no header was sent, reason=invalid when a non-blank value did not match.
			// The tag is structural only; the key itself never appears here or in any log.
			metrics.authFailure(provided == null || provided.isBlank()
					? AuditMetrics.AUTH_FAILURE_REASON_MISSING
					: AuditMetrics.AUTH_FAILURE_REASON_INVALID);
		}
		filterChain.doFilter(request, response);
	}
}
