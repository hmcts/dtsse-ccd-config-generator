package uk.gov.hmcts.ccd.sdk.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
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
      if (hasJackson3Properties(environment)) {
        throw new IllegalStateException(
            "The CCD SDK requires Jackson 2 settings to use 'spring.jackson2.*' on Spring Boot 4. "
                + "Properties under 'spring.jackson.*' configure Jackson 3 and do not preserve the "
                + "CCD SDK's Jackson 2 wire contracts.");
      }
      String configuredMapper = environment.getProperty(PREFERRED_JSON_MAPPER);
      if (configuredMapper != null && !JACKSON_2.equalsIgnoreCase(configuredMapper)) {
        throw new IllegalStateException(
            ("The CCD SDK requires '%s=%s' on Spring Boot 4 because CCD case data uses "
                + "Jackson 2 wire contracts. Jackson 3 may be present only as a transitive "
                + "infrastructure dependency and must not replace Jackson 2 for HTTP conversion.")
                .formatted(PREFERRED_JSON_MAPPER, JACKSON_2));
      }
      environment.getPropertySources().addLast(new MapPropertySource(
          "ccdSdkJacksonDefaults",
          Map.of(PREFERRED_JSON_MAPPER, JACKSON_2)));
    }
  }

  private static boolean hasJackson3Properties(ConfigurableEnvironment environment) {
    return environment.getPropertySources().stream()
        .filter(EnumerablePropertySource.class::isInstance)
        .map(EnumerablePropertySource.class::cast)
        .flatMap(source -> Arrays.stream(source.getPropertyNames()))
        .map(property -> property.toLowerCase(Locale.ROOT).replace('_', '.'))
        .anyMatch(property -> property.startsWith("spring.jackson."));
  }
}
