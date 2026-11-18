package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleSystemUser;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Case bundles kept current on the job queue, opt-in through ccd.bundling.job.enabled=true. The
 * queue table bundling.bundle_job is created by the SDK's standard library migration, registered
 * in BundleJobFlywayAutoConfiguration.
 */
@AutoConfiguration(before = BundlingAutoConfiguration.class,
    afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.bundling.job", name = "enabled")
@EnableConfigurationProperties(BundleJobProperties.class)
public class BundleJobAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public BundleJobRepository bundleJobRepository(NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    return new BundleJobRepository(jdbc, transactions);
  }

  @Bean
  @ConditionalOnMissingBean
  public BundleJobRetryPolicy bundleJobRetryPolicy(BundleJobProperties properties) {
    BundleJobProperties.Retry retry = properties.getRetry();
    return new BundleJobRetryPolicy(retry.getMaxAttempts(), retry.getInitialDelay(),
        retry.getMultiplier(), retry.getMaxDelay());
  }

  @Bean
  @ConditionalOnMissingBean(BundleDocumentStore.class)
  CdamBundleDocumentStore bundleDocumentStore(
      CaseDocumentClientApi client, AuthTokenGenerator serviceTokens, BundleSystemUser user) {
    return new CdamBundleDocumentStore(client, serviceTokens, user);
  }

  @Bean
  @ConditionalOnMissingBean(CdamBundleDocumentResolver.class)
  CdamBundleDocumentResolver cdamBundleDocumentResolver(
      CaseDocumentClientApi client, AuthTokenGenerator serviceTokens, BundleSystemUser user) {
    return new CdamBundleDocumentResolver(client, serviceTokens, user);
  }

  @Bean
  ManagedCaseBundles managedCaseBundles(BundleJobRepository repository, BundleDocumentStore store,
      PlatformTransactionManager transactions, List<CaseBundleDefinition> definitions,
      @Value("${ccd.bundling.refresh:}") String refreshKey) {
    return new ManagedCaseBundles(repository, store, transactions, definitions, refreshKey);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "ccd.bundling.job.worker", name = "enabled",
      matchIfMissing = true)
  public BundleJobWorker bundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      ManagedCaseBundles bundles, BundleJobRetryPolicy retryPolicy, BundleJobProperties properties) {
    BundleJobProperties.Worker worker = properties.getWorker();
    return new BundleJobWorker(repository, renderer, bundles, retryPolicy, worker.getBatchSize(),
        worker.getMaxConcurrentRenders(), worker.getLeaseDuration());
  }
}
