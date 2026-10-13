package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

/**
 * Accepts the S2S token the event helper sends, as the real validator would for CCD data store,
 * and passes every other token to the application's own validator.
 */
final class TestServiceAuthorisation implements BeanPostProcessor {

  static final String TOKEN = "Bearer ccd-sdk-test-s2s";
  static final String SERVICE_NAME = "ccd_data";

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean instanceof AuthTokenValidator validator ? new Validator(validator) : bean;
  }

  private record Validator(AuthTokenValidator delegate) implements AuthTokenValidator {

    @Override
    public void validate(String token) {
      if (!isTestToken(token)) {
        delegate.validate(token);
      }
    }

    @Override
    public void validate(String token, List<String> roles) {
      if (!isTestToken(token)) {
        delegate.validate(token, roles);
      }
    }

    @Override
    public String getServiceName(String token) {
      return isTestToken(token) ? SERVICE_NAME : delegate.getServiceName(token);
    }

    private static boolean isTestToken(String token) {
      return TOKEN.equals(token) || TOKEN.substring("Bearer ".length()).equals(token);
    }
  }
}
