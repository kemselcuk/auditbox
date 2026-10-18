package io.auditbox.config;

/**
 * The public (permitAll) top-level paths of the security chain (decision D5): the
 * actuator health endpoints and the static demo page. Kept as a single helper so the
 * auth-failure counter of {@link ApiKeyAuthenticationFilter} (Milestone 9.4) can skip
 * exactly the requests the chain answers without any key — anonymous health probes and
 * demo-page loads are not authentication failures.
 *
 * <p>This mirrors the request matchers of {@code ApiKeySecurityConfiguration}; it is not
 * an independent security mechanism and grants nothing by itself.
 */
final class PublicPaths {

	private static final String HEALTH_PREFIX = "/actuator/health";

	private static final String DEMO = "/demo";

	private PublicPaths() {
	}

	/**
	 * @param requestPath the value of {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()}
	 */
	static boolean matches(String requestPath) {
		return requestPath != null
				&& (requestPath.equals(HEALTH_PREFIX) || requestPath.startsWith(HEALTH_PREFIX + "/")
						|| requestPath.equals(DEMO) || requestPath.startsWith(DEMO + "/"));
	}
}
