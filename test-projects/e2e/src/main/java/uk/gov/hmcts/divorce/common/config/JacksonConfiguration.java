package uk.gov.hmcts.divorce.common.config;

import static tools.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS;
import static tools.jackson.databind.MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS;
import static tools.jackson.databind.MapperFeature.INFER_BUILDER_TYPE_BINDINGS;
import static tools.jackson.databind.cfg.EnumFeature.READ_ENUMS_USING_TO_STRING;
import static tools.jackson.databind.cfg.EnumFeature.WRITE_ENUMS_USING_TO_STRING;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.reform.ccd.document.am.healthcheck.InternalHealth;

@Configuration
public class JacksonConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder
            .configure(ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .disable(ALLOW_FINAL_FIELDS_AS_MUTATORS)
            .enable(INFER_BUILDER_TYPE_BINDINGS)
            .enable(READ_ENUMS_USING_TO_STRING)
            .enable(WRITE_ENUMS_USING_TO_STRING)
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL));
    }
}
