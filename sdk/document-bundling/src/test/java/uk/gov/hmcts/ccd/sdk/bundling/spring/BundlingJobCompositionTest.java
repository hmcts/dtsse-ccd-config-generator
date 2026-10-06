package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobCompletionHandler;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobWorker;
import uk.gov.hmcts.ccd.sdk.bundling.job.OutboxBundleJobService;

class BundlingJobCompositionTest {
  // The job configuration is deliberately listed first; @AutoConfiguration(before = ...) must win.
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class))
      .withPropertyValues("ccd.bundling.job.enabled=true")
      .withBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(
          new DriverManagerDataSource("jdbc:postgresql://localhost/unused")));

  private static final BundleJobCompletionHandler HANDLER = (job, jobContext, request, result) -> null;

  private ApplicationContextRunner withRenderer(ApplicationContextRunner base) {
    return base.withBean("caseDocuments", DocumentResolver.class, () -> new DocumentResolver() {
      @Override
      public String provider() {
        return "case-documents";
      }

      @Override
      public ResolvedDocuments resolveAll(List<DocumentReference> refs,
          BundleExecutionContext context) {
        return ResolvedDocuments.allResolved(Map.of());
      }
    });
  }

  @Test
  void rendererJdbcTemplateAndCompletionHandlerBringTheWorkerUp() {
    withRenderer(runner).withBean(BundleJobCompletionHandler.class, () -> HANDLER).run(context -> {
      assertThat(context).hasSingleBean(BundleRenderer.class);
      assertThat(context).hasSingleBean(OutboxBundleJobService.class);
      assertThat(context).hasSingleBean(BundleJobWorker.class);
    });
  }

  @Test
  void withoutARendererTheOutboxStillWorksAndTheWorkerBacksOff() {
    runner.withBean(BundleJobCompletionHandler.class, () -> HANDLER).run(context -> {
      assertThat(context).doesNotHaveBean(BundleRenderer.class);
      assertThat(context).hasSingleBean(OutboxBundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
    });
  }

  @Test
  void missingCompletionHandlerFailsStartupDescriptively() {
    withRenderer(runner).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).rootCause()
          .hasMessageContaining("exactly one BundleJobCompletionHandler")
          .hasMessageContaining("found 0");
    });
  }

  @Test
  void theJobOutboxIsOptIn() {
    withRenderer(runner).withPropertyValues("ccd.bundling.job.enabled=false").run(context -> {
      assertThat(context).hasSingleBean(BundleRenderer.class);
      assertThat(context).doesNotHaveBean(OutboxBundleJobService.class);
    });
    ApplicationContextRunner unset = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class));
    withRenderer(unset).run(context -> {
      assertThat(context).hasSingleBean(BundleRenderer.class);
      assertThat(context).doesNotHaveBean(OutboxBundleJobService.class);
      assertThat(context).doesNotHaveBean(BundleJobWorker.class);
    });
  }
}
