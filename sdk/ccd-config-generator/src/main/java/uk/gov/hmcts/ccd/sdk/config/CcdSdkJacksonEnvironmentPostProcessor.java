package uk.gov.hmcts.ccd.sdk.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Requires the SDK's Jackson 2 wire model for HTTP contracts on Spring Boot 4.
 */
@SuppressWarnings("removal")
public final class CcdSdkJacksonEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String PREFERRED_JSON_MAPPER = "spring.http.converters.preferred-json-mapper";
  static final String JACKSON_2 = "jackson2";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment,
      SpringApplication application) {
    String bootVersion = SpringBootVersion.getVersion();
    if (bootVersion != null && bootVersion.startsWith("4.")) {
      String configuredMapper = environment.getProperty(PREFERRED_JSON_MAPPER);
      if (configuredMapper != null && !JACKSON_2.equalsIgnoreCase(configuredMapper)) {
        throw new IllegalStateException(
            ("The CCD SDK requires '%s=%s' on Spring Boot 4 because CCD case data uses "
                + "Jackson 2 wire contracts. Jackson 3 must remain on the Spring Boot 4 "
                + "classpath, but must not replace Jackson 2 for HTTP conversion.")
                .formatted(PREFERRED_JSON_MAPPER, JACKSON_2));
      }
      environment.getPropertySources().addLast(new MapPropertySource(
          "ccdSdkJacksonDefaults",
          Map.of(PREFERRED_JSON_MAPPER, JACKSON_2)));
    }
  }
}
