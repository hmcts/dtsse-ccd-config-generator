package uk.gov.hmcts.ccd.sdk.bankholidays;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures {@link BankHolidays} from the bank_holidays schema, and its daily fetch from
 * GOV.UK, which runs when the application enables scheduling. An application's own
 * {@link BankHolidays} bean, such as {@link BankHolidays#bundled()} in tests, replaces both.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@ConditionalOnMissingBean(BankHolidays.class)
@EnableConfigurationProperties(BankHolidaysProperties.class)
public class BankHolidaysAutoConfiguration {

  private final BankHolidayStore store;

  BankHolidaysAutoConfiguration(DataSource dataSource) {
    this.store = new BankHolidayStore(dataSource);
  }

  @Bean
  @DependsOnDatabaseInitialization
  BankHolidays bankHolidays() {
    return new BankHolidays(store.load());
  }

  @Bean
  BankHolidaysRefresher bankHolidaysRefresher(BankHolidaysProperties properties, BankHolidays bankHolidays) {
    return new BankHolidaysRefresher(new GovUkBankHolidays(properties.getUrl()), store, bankHolidays);
  }
}
