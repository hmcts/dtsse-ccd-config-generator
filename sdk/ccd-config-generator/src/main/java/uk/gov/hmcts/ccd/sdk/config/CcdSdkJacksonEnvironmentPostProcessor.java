package uk.gov.hmcts.ccd.sdk.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps the SDK's Jackson 2 wire model as the default HTTP mapper on Spring Boot 4.
 */
@SuppressWarnings("removal")
public final class CcdSdkJacksonEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String PREFERRED_JSON_MAPPER = "spring.http.converters.preferred-json-mapper";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment,
      SpringApplication application) {
    environment.getPropertySources().addLast(new MapPropertySource(
        "ccdSdkJacksonDefaults",
        Map.of(PREFERRED_JSON_MAPPER, "jackson2")));
  }
}
