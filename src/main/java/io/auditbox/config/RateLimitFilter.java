package io.auditbox.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.auditbox.metrics.AuditMetrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-source rate limiting (milestone 10.2, decision D19) as a
 * {@link OncePerRequestFilter} in the same security chain as the {@code X-API-Key}
 * authentication filter. Filter order: the filter is added AFTER the authorization filter,
 * so it runs for requests that authorization already admitted and can therefore rely on
 * the {@link SecurityContext} principal {@link ApiKeyAuthenticationFilter} put there:
 *
 * <ul>
 *   <li>only authenticated (key-accepted) requests consume a token from their source's
 *       bucket; an unauthenticated request holds no real {@link Authentication} at this
 *       point (anonymous or rejected earlier by authorization) and keeps its 401 — a
 *       limiter rejection is never a 429 for it,</li>
 *   <li>public paths ({@code /actuator/health/**}, the static {@code /demo/**} page) are
 *       never limited when requested without a key, for the same reason; a request that
 *       DOES carry a valid key is an authenticated request and — like every other
 *       authenticated request — consumes a token, also on those paths,</li>
 *   <li>on rejection: HTTP 429 as RFC 9457 Problem Details
 *       ({@code application/problem+json}, title "Too Many Requests", a generic detail
 *       that names no key material or source) plus a {@code Retry-After} header with the
 *       whole seconds until at least one token is available (always at least 1);</li>
 *   <li>exactly one {@code auditbox.rate.limited} increment per emitted 429 (D19).</li>
 * </ul>
 *
 * <p>Like the other chain members this filter is stateless per request (the buckets live
 * in {@link RateLimiter}) and never logs or echoes key material. {@code ERROR} dispatches
 * are not filtered (the {@link OncePerRequestFilter} default), so rendering an error page
 * for an already-limited request consumes no second token.
 */
class RateLimitFilter extends OncePerRequestFilter {

	static final String RETRY_AFTER_HEADER = "Retry-After";

	private final RateLimiter limiter;

	private final AuditMetrics metrics;

	RateLimitFilter(RateLimiter limiter, AuditMetrics metrics) {
		this.limiter = limiter;
		this.metrics = metrics;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean authenticated = limiter.enabled() && authentication != null
				&& authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
		if (!authenticated) {
			filterChain.doFilter(request, response);
			return;
		}
		OptionalLong retryAfterSeconds = limiter.tryAcquire(authentication.getName());
		if (retryAfterSeconds.isEmpty()) {
			filterChain.doFilter(request, response);
			return;
		}
		metrics.rateLimited();
		// No key material or source name is ever written to the body, headers, or any log.
		response.setStatus(429);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds.getAsLong()));
		response.getWriter().write("""
				{"type":"about:blank","title":"Too Many Requests","status":429,
				 "detail":"Rate limit exceeded for this source. Retry after the indicated number of seconds."}""");
		response.getWriter().flush();
	}
}
