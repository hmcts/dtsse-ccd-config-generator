package uk.gov.hmcts.ccd.sdk.config;

import org.junit.Test;
import org.springframework.boot.SpringApplication;
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
            CcdSdkJacksonEnvironmentPostProcessor.JACKSON_2);

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(
        CcdSdkJacksonEnvironmentPostProcessor.PREFERRED_JSON_MAPPER))
        .isEqualTo(CcdSdkJacksonEnvironmentPostProcessor.JACKSON_2);
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
