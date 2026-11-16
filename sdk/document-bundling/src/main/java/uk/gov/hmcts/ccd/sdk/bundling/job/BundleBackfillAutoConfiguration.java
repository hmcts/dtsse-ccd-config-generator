package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleBackfillSource;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;

/** Disabled unless explicitly enabled; uses the existing managed outbox and worker. */
@AutoConfiguration(after = {ManagedCaseBundleAutoConfiguration.class, BundleJobAutoConfiguration.class})
@ConditionalOnProperty(prefix = "ccd.bundling.backfill", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BundleBackfillProperties.class)
@EnableScheduling
public class BundleBackfillAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  BundleBackfillRunner bundleBackfillRunner(
      PlatformTransactionManager transactions, ManagedCaseBundles bundles,
      List<CaseBundleDefinition> definitions, List<CaseBundleBackfillSource> sources,
      BundleBackfillProperties properties) {
    properties.validate();
    String name = properties.getDefinition();
    if (definitions.stream().noneMatch(definition -> definition.name().equals(name))) {
      throw new IllegalArgumentException("No managed bundle definition for backfill: " + name);
    }
    var matching = sources.stream().filter(source -> source.definition().equals(name)).toList();
    if (matching.size() != 1) {
      throw new IllegalArgumentException("Backfill needs exactly one CaseBundleBackfillSource for " + name);
    }
    return new BundleBackfillRunner(transactions, bundles, matching.get(0), properties);
  }
}
