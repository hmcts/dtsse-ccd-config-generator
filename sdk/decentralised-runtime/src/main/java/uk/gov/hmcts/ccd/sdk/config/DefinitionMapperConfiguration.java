package uk.gov.hmcts.ccd.sdk.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
public class DefinitionMapperConfiguration {

  /**
   * ObjectMapper used for CCD definition JSON snapshots and message payloads for strict adherence to CCD's structure.
   */
  @Bean(name = "ccd_mapper")
  @Qualifier("ccd_mapper") // require explicit qualifier rather than implicit ObjectMapper autowiring
  public ObjectMapper definitionMapper() {
    return JsonMapper.builderWithJackson2Defaults()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(MapperFeature.DETECT_PARAMETER_NAMES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
  }
}
