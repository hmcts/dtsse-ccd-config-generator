package uk.gov.hmcts.ccd.sdk.migration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/** Registers the data migration schema with the SDK's library migration coordinator. */
@AutoConfiguration
public class CcdDataMigrationFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration ccdDataMigrationSupportMigration() {
    return new SdkFlywayMigration(
        CcdDataMigrationFlywayAutoConfiguration.class,
        "ccd_data_migration",
        "classpath:ccd-data-migration-db/migration"
    );
  }
}
