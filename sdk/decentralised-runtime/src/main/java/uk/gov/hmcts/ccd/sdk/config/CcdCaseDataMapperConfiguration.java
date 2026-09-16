package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@AutoConfiguration
public class CcdCaseDataMapperConfiguration {

  public static final String CCD_CASE_DATA_OBJECT_MAPPER = "ccdCaseDataObjectMapper";

  /**
   * ObjectMapper used when serialising case data back to CCD's JSON wire shape.
   */
  @Bean(name = CCD_CASE_DATA_OBJECT_MAPPER)
  public ObjectMapper ccdCaseDataObjectMapper(ObjectMapper mapper) {
    // NON_NULL will retain eg. empty maps, required by certain contracts eg. AAC service & notice of change.
    return mapper.rebuild()
        .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
        .addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class)
        .build();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private interface IgnoreUnknownCcdCaseDetails {
  }
}
