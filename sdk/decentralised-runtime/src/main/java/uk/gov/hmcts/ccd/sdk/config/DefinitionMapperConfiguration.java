package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class DefinitionMapperConfiguration {

  /**
   * ObjectMapper used for CCD definition JSON snapshots and message payloads for strict adherence to CCD's structure.
   */
  @Bean(name = "ccd_mapper")
  @Qualifier("ccd_mapper") // require explicit qualifier rather than implicit ObjectMapper autowiring
  public ObjectMapper definitionMapper() {
    return new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES))
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }
}
