package uk.gov.hmcts.ccd.sdk.impl.cdam;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

@Configuration
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CdamAttachConfiguration {

  @Bean
  public CaseDocumentHashScanner caseDocumentHashScanner() {
    return new CaseDocumentHashScanner();
  }

  @Bean
  @ConditionalOnMissingBean(AuthTokenGenerator.class)
  @ConditionalOnBean(ServiceAuthorisationApi.class)
  public AuthTokenGenerator cdamAttachAuthTokenGenerator(
      @Value("${idam.s2s-auth.secret:}") String secret,
      @Value("${idam.s2s-auth.totp_secret:}") String totpSecret,
      @Value("${idam.s2s-auth.microservice}") String microService,
      ServiceAuthorisationApi serviceAuthorisationApi
  ) {
    String resolvedSecret = StringUtils.hasText(secret) ? secret : totpSecret;
    if (!StringUtils.hasText(resolvedSecret)) {
      throw new IllegalStateException("idam.s2s-auth.secret or idam.s2s-auth.totp_secret must be configured");
    }

    return AuthTokenGeneratorFactory.createDefaultGenerator(
        resolvedSecret,
        microService,
        serviceAuthorisationApi,
        Duration.ofMinutes(5)
    );
  }
}
