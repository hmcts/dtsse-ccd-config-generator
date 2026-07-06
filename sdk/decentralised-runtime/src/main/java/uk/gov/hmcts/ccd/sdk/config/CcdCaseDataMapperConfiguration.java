package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CcdCaseDataMapperConfiguration {

  public static final String CCD_CASE_DATA_OBJECT_MAPPER = "ccdCaseDataObjectMapper";

  /**
   * ObjectMapper used when serialising case data back to CCD's JSON wire shape.
   */
  @Bean(name = CCD_CASE_DATA_OBJECT_MAPPER)
  @Qualifier(CCD_CASE_DATA_OBJECT_MAPPER)
  public ObjectMapper ccdCaseDataObjectMapper(ObjectMapper mapper) {
    return mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }
}
