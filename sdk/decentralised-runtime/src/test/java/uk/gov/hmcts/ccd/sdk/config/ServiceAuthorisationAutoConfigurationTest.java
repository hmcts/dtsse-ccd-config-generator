package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;
import uk.gov.hmcts.reform.authorisation.validators.ServiceAuthTokenValidator;

class ServiceAuthorisationAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ServiceAuthorisationAutoConfiguration.class));

  @Test
  void shouldCreateServiceAuthTokenValidatorWhenServiceAuthorisationApiExists() {
    contextRunner
        .withBean(ServiceAuthorisationApi.class, TestServiceAuthorisationApi::new)
        .run(context -> assertThat(context)
            .hasSingleBean(AuthTokenValidator.class)
            .getBean(AuthTokenValidator.class)
            .isInstanceOf(ServiceAuthTokenValidator.class));
  }

  @Test
  void shouldBackOffWhenServiceProvidesAuthTokenValidator() {
    contextRunner
        .withBean(ServiceAuthorisationApi.class, TestServiceAuthorisationApi::new)
        .withBean(AuthTokenValidator.class, TestAuthTokenValidator::new)
        .run(context -> assertThat(context)
            .hasSingleBean(AuthTokenValidator.class)
            .getBean(AuthTokenValidator.class)
            .isInstanceOf(TestAuthTokenValidator.class));
  }

  @Test
  void shouldNotCreateValidatorWithoutServiceAuthorisationApi() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(AuthTokenValidator.class));
  }

  private static class TestServiceAuthorisationApi implements ServiceAuthorisationApi {

    @Override
    public String serviceToken(Map<String, String> signIn) {
      return "token";
    }

    @Override
    public void authorise(String authorisation, String[] roles) {
    }

    @Override
    public String getServiceName(String authorisation) {
      return "xui_webapp";
    }
  }

  private static class TestAuthTokenValidator implements AuthTokenValidator {

    @Override
    public void validate(String token) {
    }

    @Override
    public void validate(String token, List<String> services) {
    }

    @Override
    public String getServiceName(String token) {
      return "xui_webapp";
    }
  }
}
