package io.auditbox.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import io.auditbox.metrics.AuditMetrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders the 401 for missing/invalid API-key authentication as RFC 9457 Problem Details
 * (decision D5, plan task T5.1): {@code application/problem+json} with {@code title}
 * "Unauthorized" and a {@code detail} naming the {@code X-API-Key} header. All values are
 * build-time constants (no user input is echoed), so the JSON can be written literally.
 * The response carries a {@code WWW-Authenticate: X-API-Key} challenge that names the
 * required header without any credential material; the OpenAPI document (ApiKey
 * SecurityScheme, in: header) is the machine-readable companion hint for clients.
 *
 * <p>Milestone 9.4 / review F2: this entry point — not the authentication filter — counts
 * {@code auditbox.auth.failures}. It runs only when a request that actually required the key
 * was rejected, so a public path that merely looks unauthenticated (e.g. a percent-encoded
 * {@code /actuator/health} variant) can never be counted as a failed check. The reason tag is
 * structural (header absent/blank vs. present and wrong); the key value itself is never read
 * into a metric, log, or response.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

	static final String WWW_AUTHENTICATE_VALUE = "X-API-Key";

	private final AuditMetrics metrics;

	ApiKeyAuthenticationEntryPoint(AuditMetrics metrics) {
		this.metrics = metrics;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authenticationException) throws IOException {
		String provided = request.getHeader(ApiKeyAuthenticationFilter.HEADER);
		metrics.authFailure(provided == null || provided.isBlank()
				? AuditMetrics.AUTH_FAILURE_REASON_MISSING
				: AuditMetrics.AUTH_FAILURE_REASON_INVALID);
		// No secret material is ever written to the body, the headers, or any log.
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader("WWW-Authenticate", WWW_AUTHENTICATE_VALUE);
		response.getWriter().write("""
				{"type":"about:blank","title":"Unauthorized","status":401,
				 "detail":"Missing or invalid X-API-Key header. Send the static API key in the X-API-Key header."}""");
		response.getWriter().flush();
	}
}
