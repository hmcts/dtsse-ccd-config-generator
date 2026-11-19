package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.jackson.CcdObjectMapperCustomizer;

@AutoConfiguration
public class CcdWireFormatConfiguration {

  /**
   * Make CCD's CaseDetails class forward compatible to new fields
   * by mixing in a @JsonIgnoreProperties during deserialisation.
   */
  @Bean
  public static CcdObjectMapperCustomizer ccdCaseDetailsUnknownFieldsObjectMapperCustomizer() {
    return mapper -> mapper.addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private interface IgnoreUnknownCcdCaseDetails {
  }
}
