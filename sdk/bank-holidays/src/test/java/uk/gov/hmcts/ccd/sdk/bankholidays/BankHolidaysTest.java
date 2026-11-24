package uk.gov.hmcts.ccd.sdk.bankholidays;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BankHolidaysTest {

  private static final BankHoliday OLD =
      new BankHoliday(Division.ENGLAND_AND_WALES, LocalDate.of(2026, 12, 25), "Christmas Day", "");
  private static final BankHoliday NEW =
      new BankHoliday(Division.ENGLAND_AND_WALES, LocalDate.of(2026, 12, 28), "Boxing Day", "Substitute day");

  @Test
  @Timeout(10)
  void keepsAReloadThatReadsWhileTheFirstLookupIsReading() throws Exception {
    CountDownLatch firstReading = new CountDownLatch(1);
    CountDownLatch finishFirstRead = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    BankHolidays bankHolidays = BankHolidays.loadedOnFirstUse(() -> {
      if (reads.incrementAndGet() > 1) {
        return List.of(NEW);
      }
      firstReading.countDown();
      await(finishFirstRead);
      return List.of(OLD);
    });

    CompletableFuture<List<BankHoliday>> firstLookup =
        CompletableFuture.supplyAsync(() -> bankHolidays.holidays(Division.ENGLAND_AND_WALES));
    CompletableFuture<Void> reload = null;
    try {
      assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();
      reload = CompletableFuture.runAsync(bankHolidays::reload);
      // Time enough for a reload that did not wait to publish before the first read finishes.
      Thread.sleep(200);
    } finally {
      finishFirstRead.countDown();
    }
    CompletableFuture.allOf(firstLookup, reload).get(5, TimeUnit.SECONDS);

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
