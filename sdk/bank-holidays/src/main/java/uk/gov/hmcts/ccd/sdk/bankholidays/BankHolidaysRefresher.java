package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Hourly, and at startup, fetches GOV.UK's bank holidays into the store when the last fetch is a day
 * old, one instance for all, then reads the store again, so each instance picks up a fetch another
 * made. A failed fetch keeps the bank holidays there were and is tried again an hour later. Runs when
 * the application enables scheduling.
 */
@Slf4j
class BankHolidaysRefresher {

  private final GovUkBankHolidays govUk;
  private final BankHolidayStore store;
  private final BankHolidays bankHolidays;

  BankHolidaysRefresher(GovUkBankHolidays govUk, BankHolidayStore store, BankHolidays bankHolidays) {
    this.govUk = govUk;
    this.store = store;
    this.bankHolidays = bankHolidays;
  }

  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
  void refresh() {
    if (govUk != null && store.claimFetch()) {
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
    bankHolidays.replace(store.load());
  }
}
