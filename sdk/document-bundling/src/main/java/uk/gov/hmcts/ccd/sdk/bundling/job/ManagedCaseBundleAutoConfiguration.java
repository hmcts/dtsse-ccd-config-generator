package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleSystemUser;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/** Opt-in managed lifecycle, sharing the existing outbox worker and renderer. */
@AutoConfiguration(before = {BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class})
@ConditionalOnProperty(prefix = "ccd.bundling.managed", name = "enabled", havingValue = "true")
public class ManagedCaseBundleAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(BundleDocumentStore.class)
  CdamBundleDocumentStore bundleDocumentStore(
      CaseDocumentClientApi client, AuthTokenGenerator serviceTokens, BundleSystemUser user) {
    return new CdamBundleDocumentStore(client, serviceTokens, user);
  }

  @Bean
  @ConditionalOnMissingBean(CdamBundleDocumentResolver.class)
  @ConditionalOnProperty(
      prefix = "ccd.bundling.managed.cdam-resolver",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  CdamBundleDocumentResolver cdamBundleDocumentResolver(
      CaseDocumentClientApi client, AuthTokenGenerator serviceTokens, BundleSystemUser user) {
    return new CdamBundleDocumentResolver(client, serviceTokens, user);
  }

  @Bean
  ManagedCaseBundles managedCaseBundles(
      NamedParameterJdbcTemplate jdbc,
      OutboxBundleJobService jobs,
      BundleDocumentStore store,
      SystemEventExecutor events,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions) {
    return new ManagedCaseBundles(jdbc, jobs, store, events, transactions, definitions);
  }
}
