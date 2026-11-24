package uk.gov.hmcts.ccd.sdk.bankholidays;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Fetches GOV.UK's bank holidays into the store, then reads the store again, so each instance picks
 * up a fetch another made. A failed fetch keeps the bank holidays there were. Runs when the
 * application enables scheduling.
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

  @Scheduled(
      initialDelayString = "${ccd.bank-holidays.refresh.initial-delay:PT0S}",
      fixedDelayString = "${ccd.bank-holidays.refresh.delay:PT6H}")
  void refresh() {
    if (govUk != null) {
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
