package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.net.URI;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures {@link BankHolidays} from the bank_holidays schema, and its refresh from GOV.UK,
 * which runs when the application enables scheduling.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(BankHolidaysProperties.class)
public class BankHolidaysAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  BankHolidayStore bankHolidayStore(DataSource dataSource) {
    return new BankHolidayStore(dataSource, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  public BankHolidays bankHolidays(BankHolidayStore store) {
    return new BankHolidays(store::load);
  }

  @Bean
  BankHolidaysRefresher bankHolidaysRefresher(BankHolidaysProperties properties, BankHolidayStore store,
      BankHolidays bankHolidays) {
    GovUkBankHolidays govUk = properties.getUrl() == null || properties.getUrl().isBlank() ? null
        : new GovUkBankHolidays(URI.create(properties.getUrl()), properties.getTimeout());
    return new BankHolidaysRefresher(govUk, store, bankHolidays);
  }
}
