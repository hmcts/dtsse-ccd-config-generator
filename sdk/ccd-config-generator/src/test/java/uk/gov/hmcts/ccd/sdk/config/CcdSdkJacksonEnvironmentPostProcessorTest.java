package uk.gov.hmcts.ccd.sdk.config;

import java.util.Map;
import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CcdSdkJacksonEnvironmentPostProcessorTest {

  private final CcdSdkJacksonEnvironmentPostProcessor processor =
      new CcdSdkJacksonEnvironmentPostProcessor();

  @Test
  public void defaultsSpringBoot4HttpConversionToJackson2() {
    MockEnvironment environment = new MockEnvironment();

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(
        CcdSdkJacksonEnvironmentPostProcessor.PREFERRED_JSON_MAPPER))
        .isEqualTo(CcdSdkJacksonEnvironmentPostProcessor.JACKSON_2);
  }

  @Test
  public void allowsAnExplicitJackson2Configuration() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(
            CcdSdkJacksonEnvironmentPostProcessor.PREFERRED_JSON_MAPPER,
            CcdSdkJacksonEnvironmentPostProcessor.JACKSON_2)
        .withProperty("spring.jackson2.default-property-inclusion", "non_null");

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(
        CcdSdkJacksonEnvironmentPostProcessor.PREFERRED_JSON_MAPPER))
        .isEqualTo(CcdSdkJacksonEnvironmentPostProcessor.JACKSON_2);
  }

  @Test
  public void rejectsLegacyJacksonProperties() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("spring.jackson.default-property-inclusion", "non_null");

    assertThatThrownBy(() ->
        processor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires Jackson 2 settings to use 'spring.jackson2.*'")
        .hasMessageContaining("spring.jackson.*");
  }

  @Test
  public void rejectsLegacyJacksonPropertiesFromEnvironmentVariables() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        "testEnvironment",
        Map.of("SPRING_JACKSON_SERIALIZATION_FAIL_ON_EMPTY_BEANS", "false")));

    assertThatThrownBy(() ->
        processor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.jackson2.*");
  }

  @Test
  public void rejectsJackson3HttpConversion() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(
            CcdSdkJacksonEnvironmentPostProcessor.PREFERRED_JSON_MAPPER,
            "jackson");

    assertThatThrownBy(() ->
        processor.postProcessEnvironment(environment, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires 'spring.http.converters.preferred-json-mapper=jackson2'")
        .hasMessageContaining("Jackson 3 may be present only as a transitive infrastructure dependency");
  }
}
