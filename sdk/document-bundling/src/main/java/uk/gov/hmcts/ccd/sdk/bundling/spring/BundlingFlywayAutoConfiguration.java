package uk.gov.hmcts.ccd.sdk.bundling.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/**
 * Registers the bundling schema's migrations with the SDK's library migration coordinator. The
 * schema holds no tables now that the job outbox and managed bundles are gone; the migrations
 * still run so that databases which created them have them dropped.
 */
@AutoConfiguration
public class BundlingFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration bundlingFlywayMigration() {
    return new SdkFlywayMigration(
        BundlingFlywayAutoConfiguration.class,
        "bundling",
        "classpath:bundling-db/migration",
        DecentralisedFlywayAutoConfiguration.class);
  }
}
