package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * At startup and daily, fetches GOV.UK's bank holidays into the store. A failed fetch keeps the bank
 * holidays there were. Runs when the application enables scheduling.
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

  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.DAYS)
  void refresh() {
    try {
      store.save(govUk.fetch());
      bankHolidays.replace(store.load());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("GOV.UK bank holidays could not be fetched; keeping those there were", e);
    }
  }
}
