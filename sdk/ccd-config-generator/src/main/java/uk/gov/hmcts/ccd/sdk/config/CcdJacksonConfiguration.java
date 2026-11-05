package uk.gov.hmcts.ccd.sdk.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.jackson.CcdObjectMapperCustomizer;
import uk.gov.hmcts.ccd.sdk.jackson.CcdObjectMapperPostProcessor;
import uk.gov.hmcts.ccd.sdk.jackson.UnwrappedPrefixModule;

/**
 * Auto-configured so every service using the SDK gets the Jackson fixes without having to
 * component-scan {@code uk.gov.hmcts.ccd.sdk}.
 */
@AutoConfiguration
public class CcdJacksonConfiguration {

  /**
   * Applies the SDK's {@link CcdObjectMapperCustomizer}s, from this and the other SDK modules,
   * to every Jackson 2 mapper bean, including mappers a service builds itself.
   */
  @Bean
  public static CcdObjectMapperPostProcessor ccdObjectMapperPostProcessor(
      ObjectProvider<CcdObjectMapperCustomizer> customizers) {
    return new CcdObjectMapperPostProcessor(customizers);
  }

  /**
   * Registers {@link UnwrappedPrefixModule} so case data behind a prefixed {@code @JsonUnwrapped}
   * is read intact.
   */
  @Bean
  public static CcdObjectMapperCustomizer ccdUnwrappedPrefixObjectMapperCustomizer() {
    return mapper -> mapper.registerModule(new UnwrappedPrefixModule());
  }
}
