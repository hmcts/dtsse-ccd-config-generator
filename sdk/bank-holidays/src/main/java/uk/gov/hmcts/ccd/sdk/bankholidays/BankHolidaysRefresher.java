package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Loads the bank holidays at startup, then checks hourly, by default, whether GOV.UK's are a day old,
 * and if so fetches them into the store; one instance fetches for all. Each check reads the store
 * again, so each instance picks up a fetch another made. A failed fetch keeps the bank holidays
 * there were, and is tried again at the next check. Checks run when the application enables
 * scheduling.
 */
@Slf4j
class BankHolidaysRefresher implements SchedulingConfigurer, SmartInitializingSingleton {

  private final GovUkBankHolidays govUk;
  private final BankHolidayStore store;
  private final BankHolidays bankHolidays;
  private final BankHolidaysProperties properties;
  private final boolean schedulingEnabled;

  BankHolidaysRefresher(GovUkBankHolidays govUk, BankHolidayStore store, BankHolidays bankHolidays,
      BankHolidaysProperties properties, boolean schedulingEnabled) {
    this.govUk = govUk;
    this.store = store;
    this.bankHolidays = bankHolidays;
    this.properties = properties;
    this.schedulingEnabled = schedulingEnabled;
  }

  @Override
  public void afterSingletonsInstantiated() {
    bankHolidays.reload();
    if (govUk != null && !schedulingEnabled) {
      log.warn("Scheduling is not enabled, so bank holidays will not be fetched from GOV.UK and will run out "
          + "after {}. Enable it with @EnableScheduling", bankHolidays.calendar(Division.ENGLAND_AND_WALES)
          .knownUntil());
    }
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    BankHolidaysProperties.Refresh refresh = properties.getRefresh();
    registrar.addFixedDelayTask(new FixedDelayTask(this::refresh, refresh.getDelay(), refresh.getInitialDelay()));
  }

  void refresh() {
    Duration retryAfter = properties.getRefresh().getDelay();
    if (govUk != null && store.claimFetch(properties.getMaxAge(), retryAfter)) {
      try {
        store.fetched(govUk.fetch());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        store.fetchFailed("Interrupted");
      } catch (Exception e) {
        log.warn("GOV.UK bank holidays could not be fetched; keeping those there were", e);
        store.fetchFailed(String.valueOf(e));
      }
    }
    bankHolidays.reload();
  }
}
