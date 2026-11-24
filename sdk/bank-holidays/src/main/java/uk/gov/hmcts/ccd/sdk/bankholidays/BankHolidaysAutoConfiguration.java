package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Auto-configures {@link BankHolidays} from the bank_holidays schema, loaded at startup, and its
 * refresh from GOV.UK, which runs when the application enables scheduling. An application's own
 * {@link BankHolidays} bean, such as {@link BankHolidays#bundled()} in tests, replaces all of it.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@ConditionalOnMissingBean(BankHolidays.class)
@EnableConfigurationProperties(BankHolidaysProperties.class)
public class BankHolidaysAutoConfiguration {

  private final BankHolidayStore store;

  BankHolidaysAutoConfiguration(DataSource dataSource) {
    this.store = new BankHolidayStore(dataSource, Clock.systemUTC());
  }

  @Bean
  BankHolidays bankHolidays() {
    return new BankHolidays(store::load);
  }

  @Bean
  BankHolidaysRefresher bankHolidaysRefresher(BankHolidaysProperties properties, BankHolidays bankHolidays,
      ListableBeanFactory beanFactory) {
    GovUkBankHolidays govUk = properties.getRefresh().isEnabled()
        ? new GovUkBankHolidays(properties.getUrl(), properties.getTimeout()) : null;
    return new BankHolidaysRefresher(govUk, store, bankHolidays, properties,
        beanFactory.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
  }
}
