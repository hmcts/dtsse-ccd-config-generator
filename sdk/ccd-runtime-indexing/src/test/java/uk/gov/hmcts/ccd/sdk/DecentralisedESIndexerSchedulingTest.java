package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

class DecentralisedESIndexerSchedulingTest {
  private static final String INDEXER_SCHEDULER_BEAN_NAME = "ccdEsIndexerScheduler";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
      .withUserConfiguration(TestConfiguration.class);

  @Test
  void indexerSchedulerDoesNotReplaceDefaultTaskScheduler() {
    contextRunner.run(context -> {
      assertThat(context).hasBean(INDEXER_SCHEDULER_BEAN_NAME);
      assertThat(context).hasBean("taskScheduler");

      assertThat(context.getBean(INDEXER_SCHEDULER_BEAN_NAME))
          .isInstanceOf(ScheduledExecutorService.class);
      assertThat(context.getBean("taskScheduler"))
          .isInstanceOf(TaskScheduler.class);
    });
  }

  @Configuration
  @EnableScheduling
  @Import(DecentralisedESIndexer.class)
  static class TestConfiguration {
    @Bean
    JdbcTemplate jdbcTemplate() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    TransactionTemplate transactionTemplate() {
      return mock(TransactionTemplate.class);
    }
  }
}
