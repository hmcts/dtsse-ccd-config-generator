package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;

/**
 * The auto-configuration backs off correctly: the outbox is never mandatory, the worker needs a
 * renderer, and every bean yields to a consumer-defined one.
 */
class BundleJobAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BundleJobAutoConfiguration.class));

  @Configuration
  static class JdbcConfig {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
      return new NamedParameterJdbcTemplate(
          new DriverManagerDataSource("jdbc:postgresql://localhost/unused"));
    }
  }

  @Configuration
  static class RendererConfig {
    @Bean
    BundleRenderer bundleRenderer() {
      return new FakeBundleRenderer();
    }
  }

  @Configuration
  static class DataSourceConfig {
    @Bean
    javax.sql.DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:postgresql://localhost/unused");
    }
  }

  @Test
  void withoutAJdbcTemplateTheOutboxBacksOffEntirely() {
    runner.run(context -> {
      assertThat(context).doesNotHaveBean(BundleJobRepository.class);
      assertThat(context).doesNotHaveBean(BundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
      assertThat(context).hasSingleBean(BundleJobRetryPolicy.class);
      assertThat(context).hasSingleBean(BundleDocumentSelector.class);
    });
  }

  @Test
  void theUmbrellaPropertyDisablesEveryOutboxBean() {
    runner.withUserConfiguration(JdbcConfig.class, RendererConfig.class)
        .withPropertyValues("ccd.bundling.job.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(BundleJobRepository.class);
          assertThat(context).doesNotHaveBean(BundleJobService.class);
          assertThat(context).doesNotHaveBean(BundleJobWorker.class);
          assertThat(context).doesNotHaveBean(BundleJobRetryPolicy.class);
          assertThat(context).doesNotHaveBean(BundleDocumentSelector.class);
          assertThat(context).doesNotHaveBean("bundleJobFlywayMigration");
        });
  }

  @Test
  void theMigrationBacksOffWithoutADataSource() {
    // JdbcConfig contributes a NamedParameterJdbcTemplate but no DataSource bean, so the outbox
    // beans register while the module-owned Flyway migration backs off.
    runner.withUserConfiguration(JdbcConfig.class).run(context -> {
      assertThat(context).hasSingleBean(BundleJobRepository.class);
      assertThat(context).doesNotHaveBean("bundleJobFlywayMigration");
    });
  }

  @Test
  void theMigrationBacksOffWhenAutoMigrateIsTurnedOff() {
    runner.withUserConfiguration(DataSourceConfig.class)
        .withPropertyValues("ccd.bundling.job.auto-migrate=false")
        .run(context -> assertThat(context).doesNotHaveBean("bundleJobFlywayMigration"));
  }

  @Test
  void withAJdbcTemplateTheServiceAppearsButTheWorkerStillNeedsARenderer() {
    runner.withUserConfiguration(JdbcConfig.class).run(context -> {
      assertThat(context).hasSingleBean(BundleJobRepository.class);
      assertThat(context).hasSingleBean(OutboxBundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
    });
  }

  @Test
  void withAJdbcTemplateAndARendererTheWorkerRuns() {
    runner.withUserConfiguration(JdbcConfig.class, RendererConfig.class).run(context -> {
      assertThat(context).hasSingleBean(BundleJobWorker.class);
    });
  }

  @Test
  void theWorkerCanBeDisabledByProperty() {
    runner.withUserConfiguration(JdbcConfig.class, RendererConfig.class)
        .withPropertyValues("ccd.bundling.job.worker.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(BundleJobWorker.class);
          assertThat(context).hasSingleBean(OutboxBundleJobService.class);
        });
  }

  @Test
  void aConsumerDefinedJobServiceAndSelectorWin() {
    BundleJobService custom = new BundleJobService() {
      @Override
      public BundleJob submit(uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest request,
          uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public BundleJob submit(java.util.UUID externalId,
          java.util.Map<String, String> selectorParameters,
          uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Optional<BundleJob> find(java.util.UUID externalId) {
        return java.util.Optional.empty();
      }
    };
    BundleDocumentSelector customSelector = context -> {
      throw new UnsupportedOperationException();
    };
    runner.withUserConfiguration(JdbcConfig.class)
        .withBean("customJobService", BundleJobService.class, () -> custom)
        .withBean("customSelector", BundleDocumentSelector.class, () -> customSelector)
        .run(context -> {
          assertThat(context).doesNotHaveBean(OutboxBundleJobService.class);
          assertThat(context.getBean(BundleJobService.class)).isSameAs(custom);
          assertThat(context.getBean(BundleDocumentSelector.class)).isSameAs(customSelector);
        });
  }
}
