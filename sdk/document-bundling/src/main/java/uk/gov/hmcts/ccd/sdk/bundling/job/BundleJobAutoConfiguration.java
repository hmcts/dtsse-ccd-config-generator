package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;

/**
 * Auto-configuration for the durable job runner, opt-in through ccd.bundling.job.enabled=true;
 * each bean backs off to a consumer-defined one, the outbox needs a
 * NamedParameterJdbcTemplate, and the worker additionally needs a BundleRenderer, exactly one
 * BundleJobCompletionHandler and ccd.bundling.job.worker.enabled. The outbox table
 * bundling.bundle_job is created by the SDK's standard library migration, registered in
 * BundleJobFlywayAutoConfiguration.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.bundling.job", name = "enabled")
@EnableConfigurationProperties(BundleJobProperties.class)
public class BundleJobAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(NamedParameterJdbcTemplate.class)
  public BundleJobRepository bundleJobRepository(NamedParameterJdbcTemplate jdbc) {
    return new BundleJobRepository(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(NamedParameterJdbcTemplate.class)
  public OutboxBundleJobService bundleJobService(BundleJobRepository repository) {
    return new OutboxBundleJobService(repository);
  }

  @Bean
  @ConditionalOnMissingBean
  public BundleJobRetryPolicy bundleJobRetryPolicy(BundleJobProperties properties) {
    BundleJobProperties.Retry retry = properties.getRetry();
    return new BundleJobRetryPolicy(retry.getMaxAttempts(), retry.getInitialDelay(),
        retry.getMultiplier(), retry.getMaxDelay());
  }

  @Bean
  @ConditionalOnMissingBean
  public BundleDocumentSelector bundleDocumentSelector() {
    return BundleDocumentSelector.asSubmitted();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({NamedParameterJdbcTemplate.class, BundleRenderer.class})
  @ConditionalOnProperty(prefix = "ccd.bundling.job.worker", name = "enabled",
      matchIfMissing = true)
  public BundleJobWorker bundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobRetryPolicy retryPolicy,
      ObjectProvider<BundleJobCompletionHandler> completionHandlers,
      ObjectProvider<BundleProgressListener> listeners, BundleJobProperties properties) {
    List<BundleJobCompletionHandler> handlers = completionHandlers.stream().toList();
    if (handlers.size() != 1) {
      throw new IllegalStateException("The bundle job worker needs exactly one "
          + "BundleJobCompletionHandler bean to store each finished bundle, but found "
          + handlers.size() + ". Register one (it receives the open BundleResult and returns the "
          + "summary persisted with the job), or set ccd.bundling.job.worker.enabled=false.");
    }
    BundleJobProperties.Worker worker = properties.getWorker();
    return new BundleJobWorker(repository, renderer, selector, handlers.get(0), retryPolicy,
        listeners.orderedStream().toList(), worker.getBatchSize(),
        worker.getMaxConcurrentRenders(), worker.getLeaseDuration());
  }
}
