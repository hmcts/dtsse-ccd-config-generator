package uk.gov.hmcts.ccd.sdk.bundling.job;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/** Registers the job outbox schema with the SDK's library migration coordinator. */
@AutoConfiguration
public class BundleJobFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration bundleJobFlywayMigration() {
    return new SdkFlywayMigration(
        BundleJobFlywayAutoConfiguration.class,
        "bundling",
        "classpath:bundling-db/migration",
        DecentralisedFlywayAutoConfiguration.class);
  }
}
