package uk.gov.hmcts.ccd.sdk.docweave;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

@AutoConfiguration
@ConditionalOnClass(name = "org.flywaydb.core.Flyway")
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
public class DocweaveFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration docweaveFlywayMigration() {
    return new SdkFlywayMigration(
        DocweaveFlywayAutoConfiguration.class,
        "docweave",
        "classpath:docweave-db/migration",
        DecentralisedFlywayAutoConfiguration.class);
  }
}
