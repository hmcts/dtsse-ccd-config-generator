package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleSystemUser;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CdamBundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/** Case bundles kept current on the job queue; opt-in through ccd.bundling.job.enabled=true. */
@AutoConfiguration(before = {BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class})
@ConditionalOnProperty(prefix = "ccd.bundling.job", name = "enabled")
public class CaseBundleAutoConfiguration {
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
  ManagedCaseBundles managedCaseBundles(
      BundleJobRepository repository,
      BundleDocumentStore store,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions) {
    return new ManagedCaseBundles(repository, store, transactions, definitions);
  }
}
