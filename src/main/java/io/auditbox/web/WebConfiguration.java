package io.auditbox.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Servlet-level wiring of {@link RequestIdFilter} (plan Milestone 9.5). The filter is
 * registered directly with the servlet container and ordered at the very front of the
 * chain — ahead of the Spring Security filter chain (which runs at order -100) — so the
 * {@code X-Request-Id} echo reaches every response, the 401s of the API-key chain included.
 */
@Configuration
public class WebConfiguration {

	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
