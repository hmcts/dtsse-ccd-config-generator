package uk.gov.hmcts.ccd.sdk.taskmanagement;

import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.Encoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

public class TaskManagementFeignConfig {

  @Bean
  public RequestInterceptor s2sAuthHeader(AuthTokenGenerator authTokenGenerator) {
    return template -> template.header("ServiceAuthorization", authTokenGenerator.generate());
  }

  @Bean
  public Encoder feignEncoder() {
    return new SpringEncoder(taskManagementConverters());
  }

  @Bean
  public Decoder feignDecoder() {
    return new ResponseEntityDecoder(new SpringDecoder(taskManagementConverters()));
  }

  private static ObjectProvider<FeignHttpMessageConverters> taskManagementConverters() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("taskManagementJacksonConverter",
        (HttpMessageConverterCustomizer) converters -> {
          converters.clear();
          converters.add(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()));
        });
    FeignHttpMessageConverters converters = new FeignHttpMessageConverters(
        beanFactory.getBeanProvider(ClientHttpMessageConvertersCustomizer.class),
        beanFactory.getBeanProvider(HttpMessageConverterCustomizer.class));
    beanFactory.addBean("taskManagementFeignHttpMessageConverters", converters);
    return beanFactory.getBeanProvider(FeignHttpMessageConverters.class);
  }
}
