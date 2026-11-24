package uk.gov.hmcts.ccd.sdk.bankholidays;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

/** Registers the bank_holidays schema with the SDK's library migration coordinator. */
@AutoConfiguration
public class BankHolidaysFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration bankHolidaysFlywayMigration() {
    return new SdkFlywayMigration(
        BankHolidaysFlywayAutoConfiguration.class,
        "bank_holidays",
        "classpath:bank-holidays-db/migration");
  }
}
