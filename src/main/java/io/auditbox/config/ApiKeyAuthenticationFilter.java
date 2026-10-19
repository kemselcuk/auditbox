package io.auditbox.config;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless {@code X-API-Key} authentication filter (decisions D5 and D18, plan task
 * T5.1, milestone 10.1):
 *
 * <ul>
 *   <li>reads the {@code X-API-Key} header once per request and matches it against the
 *       per-source registry via {@link ApiKeyRegistry#authenticate(String)} (constant
 *       time against every stored digest),</li>
 *   <li>on success puts an authenticated {@link Authentication} into the
 *       {@link SecurityContext} — the principal is the matched entry's SOURCE NAME (the
 *       key itself is never stored in the context, log, or response) with the unchanged
 *       {@code ROLE_API_CLIENT} authority — and continues the chain,</li>
 *   <li>on failure continues the chain unauthenticated; the authorization rules
 *       ({@code anyRequest().authenticated()}) then raise the 401 (rendered as Problem
 *       Details by {@link ApiKeyAuthenticationEntryPoint}). The filter deliberately does not
 *       send the 401 itself so missing and wrong keys take the identical code path.</li>
 * </ul>
 *
 * <p>The context is stateless per request: with {@code SessionCreationPolicy.STATELESS} and
 * no {@code SecurityContextRepository}, nothing is persisted to a session or cookie.
 *
 * <p>Review F2: the filter no longer counts auth failures itself. Counting lives in
 * {@link ApiKeyAuthenticationEntryPoint}, which runs only when the key was actually required
 * and rejected — a raw-path check here mis-counted percent-encoded public paths.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	/** Header carrying the API key; the OpenAPI SecurityScheme documents the same name. */
	static final String HEADER = "X-API-Key";

	private final ApiKeyRegistry registry;

	ApiKeyAuthenticationFilter(ApiKeyRegistry registry) {
		this.registry = registry;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String provided = request.getHeader(HEADER);
		// Never log the header or its value.
		registry.authenticate(provided).ifPresent(source -> {
			Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
					source, null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
		});
		filterChain.doFilter(request, response);
	}
}
