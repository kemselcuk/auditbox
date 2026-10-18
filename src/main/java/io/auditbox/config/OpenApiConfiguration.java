package io.auditbox.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata (plan task T5.1): documents the {@code X-API-Key} authentication as an
 * {@code apiKey} SecurityScheme (in: header) with a global security requirement, so
 * {@code /v3/api-docs} declares that every operation requires the key. The document itself
 * stays protected by the same key ({@code anyRequest().authenticated()} in
 * {@link ApiKeySecurityConfiguration}); springdoc is disabled by default and enabled only
 * in the {@code dev} (and {@code test}) profiles.
 */
@Configuration
public class OpenApiConfiguration {

	@Bean
	OpenAPI auditBoxOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("AuditBox API")
						.version("v1")
						.description("Event history service: idempotent event ingestion (per eventId) "
								+ "and keyset-paginated listing. Authenticated with the static X-API-Key header."))
				.components(new Components().addSecuritySchemes("ApiKeyAuth",
						new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name(ApiKeyAuthenticationFilter.HEADER)))
				.addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"));
	}
}
