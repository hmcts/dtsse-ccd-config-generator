package uk.gov.hmcts.ccd.sdk.retention.client;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

public class CcdDataStoreRetentionFeignConfig {

  @Bean
  public RequestInterceptor serviceAuthorizationHeader(AuthTokenGenerator authTokenGenerator) {
    return template -> template.header("ServiceAuthorization", authTokenGenerator.generate());
  }
}
