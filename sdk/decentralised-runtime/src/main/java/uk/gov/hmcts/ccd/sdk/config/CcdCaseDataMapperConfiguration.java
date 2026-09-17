package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.CcdCaseDataMapper;

@AutoConfiguration
public class CcdCaseDataMapperConfiguration {

  public static final String CCD_CASE_DATA_OBJECT_MAPPER = "ccdCaseDataObjectMapper";

  /**
   * ObjectMapper used when serialising case data back to CCD's JSON wire shape.
   */
  @Bean(name = CCD_CASE_DATA_OBJECT_MAPPER)
  public ObjectMapper ccdCaseDataObjectMapper(ObjectMapper mapper) {
    // NON_NULL will retain eg. empty maps, required by certain contracts eg. AAC service & notice of change.
    var caseDataMapperBuilder = mapper.rebuild();
    CcdCaseDataMapper.configure(caseDataMapperBuilder);
    return caseDataMapperBuilder
      .changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
          JsonInclude.Include.NON_NULL,
          JsonInclude.Include.NON_NULL
      ))
      .addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class)
      .build();
  }

  @Bean
  public JsonMapperBuilderCustomizer ccdCaseDetailsMixin() {
    return builder -> builder.addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private interface IgnoreUnknownCcdCaseDetails {
  }
}
