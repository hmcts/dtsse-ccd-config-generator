package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

class TaskManagementAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class, TaskManagementAutoConfiguration.class))
      .withPropertyValues(
          "task-management.api.url=http://localhost:8080",
          "task-management.outbox.poller.enabled=false"
      )
      .withUserConfiguration(TestConfig.class);

  @Test
  void shouldRegisterFeignClientConfigurerWhenPropertyEnabled() {
    contextRunner
        .withPropertyValues("task-management.feign.inherit-parent-configuration=true")
        .run(context -> {
          assertThat(context).hasSingleBean(FeignClientConfigurer.class);
          assertThat(context.getBean(FeignClientConfigurer.class).inheritParentConfiguration()).isTrue();
        });
  }

  @Test
  void shouldNotRegisterFeignClientConfigurerWhenPropertyDisabled() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(FeignClientConfigurer.class));
  }

  @Test
  void shouldNotOverrideUserProvidedFeignClientConfigurer() {
    contextRunner
        .withPropertyValues("task-management.feign.inherit-parent-configuration=true")
        .withUserConfiguration(UserFeignConfigurerConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(FeignClientConfigurer.class);
          assertThat(context.getBean(FeignClientConfigurer.class).inheritParentConfiguration()).isFalse();
        });
  }

  @Configuration
  static class TestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
      return mock(NamedParameterJdbcTemplate.class);
    }

    @Bean
    AuthTokenGenerator authTokenGenerator() {
      return () -> "service-token";
    }
  }

  @Configuration
  static class UserFeignConfigurerConfiguration {
    @Bean
    FeignClientConfigurer userFeignClientConfigurer() {
      return new FeignClientConfigurer() {
        @Override
        public boolean inheritParentConfiguration() {
          return false;
        }
      };
    }
  }
}
