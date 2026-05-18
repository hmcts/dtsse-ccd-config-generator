package uk.gov.hmcts.ccd.sdk.config;

import feign.FeignException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;
import uk.gov.hmcts.reform.authorisation.validators.ServiceAuthTokenValidator;

@AutoConfiguration
@ConditionalOnClass({ServiceAuthorisationApi.class, ServiceAuthTokenValidator.class, FeignException.class})
public class ServiceAuthorisationAutoConfiguration {

  @Bean
  @ConditionalOnBean(ServiceAuthorisationApi.class)
  @ConditionalOnMissingBean(AuthTokenValidator.class)
  public AuthTokenValidator serviceAuthTokenValidator(ServiceAuthorisationApi serviceAuthorisationApi) {
    return new ServiceAuthTokenValidator(serviceAuthorisationApi);
  }
}
