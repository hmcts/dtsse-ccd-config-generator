package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Checks hourly, by default, whether GOV.UK's bank holidays are a day old, and if so fetches them into
 * the store; one instance fetches for all. Then reads the store again, so each instance picks up a
 * fetch another made. A failed fetch keeps the bank holidays there were, and is tried again at the
 * next check. Runs when the application enables scheduling.
 */
@Slf4j
class BankHolidaysRefresher {

  private final GovUkBankHolidays govUk;
  private final BankHolidayStore store;
  private final BankHolidays bankHolidays;
  private final Duration maxAge;
  private final Duration retryAfter;

  BankHolidaysRefresher(GovUkBankHolidays govUk, BankHolidayStore store, BankHolidays bankHolidays,
      Duration maxAge, Duration retryAfter) {
    this.govUk = govUk;
    this.store = store;
    this.bankHolidays = bankHolidays;
    this.maxAge = maxAge;
    this.retryAfter = retryAfter;
  }

  @Scheduled(
      initialDelayString = "${ccd.bank-holidays.refresh.initial-delay:PT0S}",
      fixedDelayString = "${ccd.bank-holidays.refresh.delay:PT1H}")
  void refresh() {
    if (govUk != null && store.claimFetch(maxAge, retryAfter)) {
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
