package uk.gov.hmcts.ccd.sdk.bundling.job;

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
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;

/** A non-blank trigger enables queue population for every managed bundle definition. */
@AutoConfiguration(after = {ManagedCaseBundleAutoConfiguration.class, BundleJobAutoConfiguration.class})
@Conditional(BundleBackfillAutoConfiguration.HasTrigger.class)
@EnableScheduling
public class BundleBackfillAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  BundleBackfillRunner bundleBackfillRunner(
      NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions, ManagedCaseBundles bundles,
      List<CaseBundleDefinition> definitions,
      Environment environment, @Value("${ccd.bundling.backfill.batch-size:25}") int batchSize) {
    return new BundleBackfillRunner(jdbc, transactions, bundles, definitions,
        environment.getProperty("ccd.bundling.backfill").trim(), batchSize);
  }

  static class HasTrigger implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return StringUtils.hasText(context.getEnvironment().getProperty("ccd.bundling.backfill"));
    }
  }
}
