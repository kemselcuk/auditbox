package io.auditbox.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-request correlation ID filter (plan Milestone 9.5):
 *
 * <ul>
 *   <li>accepts an incoming {@code X-Request-Id} header only when it is 1..128 characters
 *       of {@code [A-Za-z0-9._-]}; anything else (absent, longer, other characters, control
 *       characters) is replaced by a generated UUID — an untrusted id is never echoed,</li>
 *   <li>stores the id under the {@code requestId} MDC key so every log line of the request
 *       carries it (visible in prod, where ECS structured logging serializes the MDC, and
 *       in any pattern that includes {@code %X{requestId}}),</li>
 *   <li>echoes the id in the {@code X-Request-Id} response header — including on 401
 *       responses, because this filter runs before the security chain (see
 *       {@link WebConfiguration}),</li>
 *   <li>clears the MDC in a {@code finally} block so a pooled thread can never leak a
 *       previous request's id into the next one's log lines.</li>
 * </ul>
 */
public class RequestIdFilter extends OncePerRequestFilter {

	/** Incoming request header and echoed response header — both use the same name. */
	public static final String HEADER = "X-Request-Id";

	/** MDC key under which the correlation id is visible to every log statement. */
	public static final String MDC_KEY = "requestId";

	/** 1..128 characters of [A-Za-z0-9._-] — the only accepted incoming header shape. */
	static final Pattern SAFE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String requestId = incoming == null ? newRequestId() : requestIdFor(incoming);
		try {
			MDC.put(MDC_KEY, requestId);
			response.setHeader(HEADER, requestId);
			filterChain.doFilter(request, response);
		}
		finally {
			// Pooled threads are reused across requests: never leak the id into the next
			// request's log lines.
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * Decision logic, package-private so the tests exercise exactly what production uses:
	 * the incoming id is echoed verbatim only when it matches {@link #SAFE_PATTERN};
	 * anything else yields a fresh UUID.
	 */
	static String requestIdFor(String incoming) {
		if (incoming != null && SAFE_PATTERN.matcher(incoming).matches()) {
			return incoming;
		}
		return newRequestId();
	}

	private static String newRequestId() {
		return UUID.randomUUID().toString();
	}
}
