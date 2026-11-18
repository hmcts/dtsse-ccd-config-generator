package uk.gov.hmcts.ccd.sdk.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Applies every {@link CcdObjectMapperCustomizer} bean to each {@link ObjectMapper} bean after
 * initialisation. Spring only offers a {@code FactoryBean}'s product, such as a
 * {@code Jackson2ObjectMapperFactoryBean}'s mapper, to the after-initialisation callback.
 */
public class CcdObjectMapperPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<CcdObjectMapperCustomizer> customizers;

  public CcdObjectMapperPostProcessor(ObjectProvider<CcdObjectMapperCustomizer> customizers) {
    this.customizers = customizers;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ObjectMapper mapper) {
      customizers.orderedStream().forEach(customizer -> customizer.customize(mapper));
    }
    return bean;
  }
}
