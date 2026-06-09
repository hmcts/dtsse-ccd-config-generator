package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

@AutoConfiguration
public class CcdWireFormatConfiguration {

  /**
   * Make CCD's CaseDetails class forward compatible to new fields
   * by mixing in a @JsonIgnoreProperties during deserialisation.
   */
  @Bean
  public static BeanPostProcessor ccdCaseDetailsUnknownFieldsObjectMapperPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ObjectMapper mapper) {
          mapper.addMixIn(CaseDetails.class, IgnoreUnknownCcdCaseDetails.class);
        }
        return bean;
      }
    };
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private interface IgnoreUnknownCcdCaseDetails {
  }
}
