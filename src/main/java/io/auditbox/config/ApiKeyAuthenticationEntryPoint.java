package io.auditbox.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders the 401 for missing/invalid API-key authentication as RFC 9457 Problem Details
 * (decision D5, plan task T5.1): {@code application/problem+json} with {@code title}
 * "Unauthorized" and a {@code detail} naming the {@code X-API-Key} header. All values are
 * build-time constants (no user input is echoed), so the JSON can be written literally.
 * The response carries no WWW-Authenticate challenge equivalent — the OpenAPI document
 * (ApiKey SecurityScheme, in: header) is the machine-readable hint for clients except for
 * the standard header added below, which names the header without any credential material.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

	static final String WWW_AUTHENTICATE_VALUE = "X-API-Key";

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authenticationException) throws IOException {
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
