package uk.gov.hmcts.ccd.sdk;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.MapperBuilder;

/**
 * Preserves Jackson 2 case-data binding semantics when Spring Boot 4 runs with
 * {@code spring.jackson.use-jackson2-defaults=false}.
 */
public final class CcdCaseDataMapper {

  private CcdCaseDataMapper() {
  }

  public static void configure(MapperBuilder<? extends ObjectMapper, ?> builder) {
    builder.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);
    builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    builder.disable(EnumFeature.READ_ENUMS_USING_TO_STRING);
    builder.disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING);
  }
}
