package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/**
 * Registers the job outbox's schema with the SDK's library migration coordinator. Its location is
 * still {@code bundling-db}, where document-bundling created it, so existing databases carry on.
 */
@AutoConfiguration
public class DocToolkitFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration docToolkitFlywayMigration() {
    return new SdkFlywayMigration(
        DocToolkitFlywayAutoConfiguration.class,
        "bundling",
        "classpath:bundling-db/migration",
        DecentralisedFlywayAutoConfiguration.class);
  }
}
