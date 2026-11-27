package uk.gov.hmcts.ccd.sdk.jobs;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/** Registers the job_utils schema with the SDK's library migration coordinator. */
@AutoConfiguration
public class JobUtilsFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration jobUtilsFlywayMigration() {
    return new SdkFlywayMigration(
        JobUtilsFlywayAutoConfiguration.class,
        "job_utils",
        "classpath:job-utils-db/migration",
        DecentralisedFlywayAutoConfiguration.class);
  }
}
