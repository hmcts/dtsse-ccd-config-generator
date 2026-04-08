package uk.gov.hmcts.ccd.sdk.taskmanagement.delay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DelayUntilAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DelayUntilAutoConfiguration.class))
      .withUserConfiguration(TestConfig.class);

  @Test
  void shouldRegisterDelayBeansViaAutoConfiguration() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(DelayUntilResolver.class);
      assertThat(context).hasBean("delayUntilDateCalculator");
      assertThat(context).hasBean("delayUntilDateTimeCalculator");
      assertThat(context).hasBean("delayUntilIntervalCalculator");
      assertThat(context).hasBean("workingDayIndicator");
      assertThat(context).hasBean("publicHolidaysCollection");
      assertThat(context).hasBean("publicHolidayService");
      assertThat(context).hasBean("calendarCacheManager");
      assertThat(context).doesNotHaveBean("org.springframework.cache.config.internalCacheAdvisor");
      assertThat(context.getBean("calendarCacheManager")).isInstanceOf(CaffeineCacheManager.class);
    });
  }

  @Test
  void shouldCalculateDelayUntilUsingAutoConfiguredBean() {
    contextRunner.run(context -> {
      DelayUntilResolver delayUntilResolver = context.getBean(DelayUntilResolver.class);

      LocalDateTime delayUntil = delayUntilResolver.resolve(
          DelayUntilRequest.builder()
              .delayUntil("2026-03-20T14:30:00")
              .build()
      );

      assertThat(delayUntilResolver).isNotNull();
      assertThat(delayUntil).isEqualTo(LocalDateTime.of(2026, 3, 20, 14, 30));
    });
  }

  @Configuration
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
