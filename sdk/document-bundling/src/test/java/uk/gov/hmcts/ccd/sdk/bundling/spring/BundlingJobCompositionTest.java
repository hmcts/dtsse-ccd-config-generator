package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobRepository;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobWorker;
import uk.gov.hmcts.ccd.sdk.bundling.job.ManagedCaseBundles;

/** The enabled composition is exercised by ManagedCaseBundlesIntegrationTest. */
class BundlingJobCompositionTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class));

  @Test
  void theJobQueueIsOptIn() {
    for (ApplicationContextRunner off
        : new ApplicationContextRunner[] {runner, runner.withPropertyValues("ccd.bundling.job.enabled=false")}) {
      off.run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(BundleJobRepository.class);
        assertThat(context).doesNotHaveBean(ManagedCaseBundles.class);
        assertThat(context).doesNotHaveBean(BundleJobWorker.class);
      });
    }
  }
}
