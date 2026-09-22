package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.jackson.UnwrappedPrefixModule;

@Configuration
public class CcdJacksonConfiguration {

  /**
   * Registers {@link UnwrappedPrefixModule} on every Jackson 2 mapper bean, including mappers a
   * service builds itself, so case data behind a prefixed {@code @JsonUnwrapped} is read intact.
   */
  @Bean
  public static BeanPostProcessor ccdUnwrappedPrefixObjectMapperPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ObjectMapper mapper) {
          mapper.registerModule(new UnwrappedPrefixModule());
        }
        return bean;
      }
    };
  }
}
