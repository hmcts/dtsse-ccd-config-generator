package uk.gov.hmcts.ccd.sdk.bankholidays;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Auto-configures {@link BankHolidays} from the bank_holidays schema, loaded at startup, and its
 * refresh from GOV.UK, which runs when the application enables scheduling. An application's own
 * {@link BankHolidays} bean, such as {@link BankHolidays#bundled()} in tests, replaces all of it.
 */
@Slf4j
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
  BankHolidaysRefresher bankHolidaysRefresher(BankHolidaysProperties properties, BankHolidays bankHolidays,
      ListableBeanFactory beanFactory) {
    if (properties.isFetch()
        && !beanFactory.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
      log.warn("Scheduling is not enabled, so bank holidays will not be fetched from GOV.UK and will run out "
          + "after {}. Enable it with @EnableScheduling",
          bankHolidays.calendar(Division.ENGLAND_AND_WALES).knownUntil());
    }
    return new BankHolidaysRefresher(properties.isFetch() ? new GovUkBankHolidays(properties.getUrl()) : null,
        store, bankHolidays);
  }
}
