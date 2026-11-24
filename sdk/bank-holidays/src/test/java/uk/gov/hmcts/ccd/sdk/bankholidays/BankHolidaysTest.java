package uk.gov.hmcts.ccd.sdk.bankholidays;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class BankHolidaysTest {

  private static final BankHoliday OLD =
      new BankHoliday(Division.ENGLAND_AND_WALES, LocalDate.of(2026, 12, 25), "Christmas Day", "");
  private static final BankHoliday NEW =
      new BankHoliday(Division.ENGLAND_AND_WALES, LocalDate.of(2026, 12, 28), "Boxing Day", "Substitute day");

  @Test
  void keepsARefreshPublishedWhileTheFirstLookupIsReading() throws Exception {
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch finishReading = new CountDownLatch(1);
    BankHolidays bankHolidays = BankHolidays.loadedOnFirstUse(() -> {
      reading.countDown();
      await(finishReading);
      return List.of(OLD);
    });

    CompletableFuture<List<BankHoliday>> firstLookup =
        CompletableFuture.supplyAsync(() -> bankHolidays.holidays(Division.ENGLAND_AND_WALES));
    reading.await();
    Thread refresh = new Thread(() -> bankHolidays.replace(List.of(NEW)));
    refresh.start();
    while (refresh.isAlive() && refresh.getState() != Thread.State.BLOCKED) {
      Thread.onSpinWait();
    }
    finishReading.countDown();
    refresh.join();
    firstLookup.join();

    assertThat(bankHolidays.holidays(Division.ENGLAND_AND_WALES)).containsExactly(NEW);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
