package uk.gov.hmcts.ccd.sdk.taskmanagement;

import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.Encoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

public class TaskManagementFeignConfig {

  @Bean
  public RequestInterceptor s2sAuthHeader(AuthTokenGenerator authTokenGenerator) {
    return template -> template.header("ServiceAuthorization", authTokenGenerator.generate());
  }

  @Bean
  public Encoder feignEncoder(ObjectProvider<FeignHttpMessageConverters> converters) {
    return new SpringEncoder(converters);
  }

  @Bean
  public Decoder feignDecoder(ObjectProvider<FeignHttpMessageConverters> converters) {
    return new ResponseEntityDecoder(new SpringDecoder(converters));
  }
}
