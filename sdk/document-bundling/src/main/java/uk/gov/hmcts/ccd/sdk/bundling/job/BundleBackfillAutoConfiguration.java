package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleBackfillSource;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;

/** A non-blank trigger enables queue population for every registered application source. */
@AutoConfiguration(after = {ManagedCaseBundleAutoConfiguration.class, BundleJobAutoConfiguration.class})
@Conditional(BundleBackfillAutoConfiguration.HasTrigger.class)
@EnableScheduling
public class BundleBackfillAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  BundleBackfillRunner bundleBackfillRunner(
      NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions, ManagedCaseBundles bundles,
      List<CaseBundleDefinition> definitions, List<CaseBundleBackfillSource> sources,
      Environment environment, @Value("${ccd.bundling.backfill.batch-size:25}") int batchSize) {
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("Backfill needs at least one CaseBundleBackfillSource");
    }
    var names = new HashSet<String>();
    for (var source : sources) {
      String name = source.definition();
      if (!names.add(name)) {
        throw new IllegalArgumentException("Duplicate backfill source for " + name);
      }
      if (definitions.stream().noneMatch(definition -> definition.name().equals(name))) {
        throw new IllegalArgumentException("No managed bundle definition for backfill: " + name);
      }
    }
    return new BundleBackfillRunner(jdbc, transactions, bundles, sources,
        environment.getProperty("ccd.bundling.backfill").trim(), batchSize);
  }

  static class HasTrigger implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return StringUtils.hasText(context.getEnvironment().getProperty("ccd.bundling.backfill"));
    }
  }
}
