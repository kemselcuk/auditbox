package io.auditbox.events;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hibernate JSON (de)serialization for the {@code metadata jsonb} column (Stage 3, D10).
 *
 * <p>Hibernate 7.4 maps every attribute annotated {@code @JdbcTypeCode(SqlTypes.JSON)} through
 * a pluggable {@link FormatMapper}. Its built-in Jackson 3 default
 * ({@code Jackson3JsonFormatMapper}) reads JSON numbers as double nodes (shortest round-trip
 * double), which would corrupt high-precision client decimals on the <em>read</em> path:
 * {@code 1.00000000000000000001} would come back from the database as {@code 1.0}. This
 * configuration therefore registers Hibernate's {@code hibernate.type.json_format_mapper}
 * strategy explicitly with a Jackson 3 mapper that enables
 * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}, so stored metadata is read back
 * digit-exact and a duplicate check/409 response renders the stored row faithfully (D10).
 *
 * <p>The write path is exact either way (a BigDecimal tree serializes digit-exact); the HTTP
 * request/response side is configured separately via the Boot property
 * {@code spring.jackson.deserialization.use-big-decimal-for-floats} (application.yml), which
 * customizes the Spring MVC JsonMapper. The end-to-end precision behavior is proven by
 * {@code EventApiControllerTest}.
 */
@Configuration
class JsonFormatMapperConfiguration {

	/**
	 * The {@link FormatMapper} Hibernate uses for all JSON attributes: a Jackson 3
	 * {@code Jackson3JsonFormatMapper} whose mapper reads numbers as exact decimals.
	 */
	@Bean
	FormatMapper hibernateJsonFormatMapper() {
		return new Jackson3JsonFormatMapper(JsonMapper.builder()
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.build());
	}

	/**
	 * Hands the mapper to Hibernate via the {@code hibernate.type.json_format_mapper} setting
	 * ({@link MappingSettings#JSON_FORMAT_MAPPER}); passing the instance avoids class-name
	 * based strategy resolution.
	 */
	@Bean
	HibernatePropertiesCustomizer hibernateJsonFormatMapperCustomizer(FormatMapper hibernateJsonFormatMapper) {
		return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, hibernateJsonFormatMapper);
	}
}
