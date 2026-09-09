package uk.gov.hmcts.ccd.sdk.migration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

@AutoConfiguration
@ConditionalOnClass(name = "org.flywaydb.core.Flyway")
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
public class CcdDataMigrationFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration ccdDataMigrationSupportMigration() {
    return new SdkFlywayMigration(
        CcdDataMigrationFlywayAutoConfiguration.class,
        "ccd_data_migration",
        "classpath:ccd-data-migration-db/migration",
        DecentralisedFlywayAutoConfiguration.class
    );
  }
}
