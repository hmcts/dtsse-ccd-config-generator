package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Optional;

/** Configures the Jackson 2 mapper used exclusively for consumer case-data classes. */
public final class Jackson2CaseDataMapper {

  private Jackson2CaseDataMapper() {
  }

  public static ObjectMapper configured(Optional<ObjectMapper> applicationMapper) {
    ObjectMapper mapper = applicationMapper.orElseGet(ObjectMapper::new).copy();
    mapper.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);
    mapper.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    mapper.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    mapper.disable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
    mapper.disable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
    return mapper;
  }
}
