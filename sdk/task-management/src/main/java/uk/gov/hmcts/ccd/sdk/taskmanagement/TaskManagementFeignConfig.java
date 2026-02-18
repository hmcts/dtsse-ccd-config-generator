package uk.gov.hmcts.ccd.sdk.taskmanagement;

import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.Encoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

@Configuration
public class TaskManagementFeignConfig {

  @Bean
  public RequestInterceptor s2sAuthHeader(AuthTokenGenerator authTokenGenerator) {
    return template -> template.header("ServiceAuthorization", authTokenGenerator.generate());
  }

  @Bean
  public Encoder feignEncoder(ObjectFactory<HttpMessageConverters> converters) {
    return new SpringEncoder(converters);
  }

  @Bean
  public Decoder feignDecoder(ObjectFactory<HttpMessageConverters> converters) {
    return new ResponseEntityDecoder(new SpringDecoder(converters));
  }
}
