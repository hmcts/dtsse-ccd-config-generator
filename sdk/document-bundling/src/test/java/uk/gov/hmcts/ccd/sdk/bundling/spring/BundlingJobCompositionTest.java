package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobService;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobWorker;
import uk.gov.hmcts.ccd.sdk.bundling.job.OutboxBundleJobService;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * The renderer auto-configuration composes with {@link BundleJobAutoConfiguration} instead of
 * duplicating it: the renderer defined here satisfies the job worker's
 * {@code @ConditionalOnBean(BundleRenderer.class)} because {@link BundlingAutoConfiguration}
 * orders itself before the job configuration, and the worker backs off through its own
 * conditions when the renderer does not wire up.
 */
class BundlingJobCompositionTest {

  // The job auto-configuration is deliberately listed first; the @AutoConfiguration(before = ...)
  // ordering, not the declaration order, must put the renderer definition ahead of the worker's
  // bean condition.
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class));

  @Configuration
  static class JdbcConfig {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
      return new NamedParameterJdbcTemplate(
          new DriverManagerDataSource("jdbc:postgresql://localhost/unused"));
    }
  }

  @Configuration
  static class RendererDependenciesConfig {
    @Bean
    DocumentResolver caseDocumentsResolver() {
      return new SpringWiringFixtures.FixturePdfResolver("case-documents");
    }

    @Bean
    BundleDestination filesystemDestination() {
      return new FilesystemBundleDestination(
          Path.of(System.getProperty("java.io.tmpdir"), "bundling-job-composition-test"));
    }
  }

  @Test
  void aJdbcTemplateAndTheAutoConfiguredRendererBringTheWorkerUp() {
    runner.withUserConfiguration(JdbcConfig.class, RendererDependenciesConfig.class)
        .run(context -> {
          assertThat(context).hasSingleBean(BundleRenderer.class);
          assertThat(context).hasSingleBean(OutboxBundleJobService.class);
          assertThat(context).hasSingleBean(BundleJobWorker.class);
        });
  }

  @Test
  void withoutARendererTheOutboxStillWorksAndTheWorkerBacksOff() {
    runner.withUserConfiguration(JdbcConfig.class).run(context -> {
      assertThat(context).doesNotHaveBean(BundleRenderer.class);
      assertThat(context).hasSingleBean(BundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
    });
  }

  @Test
  void withoutAJdbcTemplateTheRendererStandsAlone() {
    runner.withUserConfiguration(RendererDependenciesConfig.class).run(context -> {
      assertThat(context).hasSingleBean(BundleRenderer.class);
      assertThat(context).doesNotHaveBean(BundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
    });
  }

  @Test
  void disablingBundlingDisablesTheWorkerButNotTheOutbox() {
    runner.withUserConfiguration(JdbcConfig.class, RendererDependenciesConfig.class)
        .withPropertyValues("ccd.bundling.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(BundleRenderer.class);
          assertThat(context).doesNotHaveBean(BundleJobWorker.class);
          assertThat(context).hasSingleBean(BundleJobService.class);
        });
  }
}
