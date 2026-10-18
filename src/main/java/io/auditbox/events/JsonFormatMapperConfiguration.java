package io.auditbox.events;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonFactory;
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
 * strategy explicitly with a Jackson 3 mapper that reads numbers as exact
 * {@link java.math.BigDecimal} so stored metadata is read back digit-exact and a duplicate
 * check/409 response renders the stored row faithfully (D10).
 *
 * <p>Two more settings keep the read path coherent with the metadata number bounds of
 * {@link ValidMetadataValidator} (review round 2, F1/F2):
 *
 * <ul>
 *   <li><strong>Number-length read constraint.</strong> PostgreSQL jsonb renders stored
 *       numbers as plain decimals, so a metadata number whose 1000 integer digits and 1000
 *       fractional digits are both in use is up to 2002 characters of plain text on the read
 *       back. Jackson's default read constraint (1000 digits per number) would reject exactly
 *       those legal values and turn an idempotent replay into a 500; raised to comfortably
 *       above the validation bound.</li>
 *   <li><strong>Plain decimal writing.</strong> Numbers are written without exponent
 *       notation ({@code 1e+3} becomes {@code 1000}), matching the API response rendering
 *       and keeping the stored/compared JSON forms stable.</li>
 * </ul>
 *
 * <p>The HTTP request/response side is configured separately via Boot properties in
 * application.yml ({@code spring.jackson.*}, which customize the Spring MVC JsonMapper).
 * The end-to-end precision behavior is proven by {@code EventApiControllerTest}.
 */
@Configuration
class JsonFormatMapperConfiguration {

	/**
	 * Read bound above every value {@link ValidMetadataValidator} can accept: at most
	 * {@code 1000} integer plus {@code 1000} fractional digits, i.e. at most 2002 plain
	 * decimal characters; 4096 keeps parsing cost trivially bounded while leaving headroom.
	 */
	static final int MAX_NUMBER_LENGTH = 4096;

	/**
	 * The {@link FormatMapper} Hibernate uses for all JSON attributes: a Jackson 3
	 * {@code Jackson3JsonFormatMapper} whose mapper reads numbers as exact decimals and
	 * writes plain decimal forms.
	 */
	@Bean
	FormatMapper hibernateJsonFormatMapper() {
		JsonFactory jsonFactory = JsonFactory.builder()
				.streamReadConstraints(StreamReadConstraints.builder()
						.maxNumberLength(MAX_NUMBER_LENGTH)
						.build())
				.build();
		return new Jackson3JsonFormatMapper(JsonMapper.builder(jsonFactory)
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
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
