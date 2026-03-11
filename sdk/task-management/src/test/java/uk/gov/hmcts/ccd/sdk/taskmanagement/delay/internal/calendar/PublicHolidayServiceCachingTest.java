package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilAutoConfiguration;

@SpringBootTest(classes = PublicHolidayServiceCachingTest.TestApplication.class)
class PublicHolidayServiceCachingTest {

  private static final String URI = "https://example.com/bank-holidays.json";

  @Autowired
  private MutableTicker calendarTicker;

  @Autowired
  private CacheManager calendarCacheManager;

  @MockitoSpyBean
  private PublicHolidayService publicHolidayService;

  @BeforeEach
  void setUp() {
    calendarTicker.reset();
    requireNonNull(calendarCacheManager.getCache("calendar_cache")).clear();
  }

  @Test
  void shouldCachePublicHolidayResponsesForTheSameUri() {
    BankHolidays first = BankHolidays.builder()
        .division("request-1")
        .events(List.of())
        .build();

    PublicHolidayService publicHolidayServiceSpy = AopTestUtils.getUltimateTargetObject(publicHolidayService);
    doReturn(first).when(publicHolidayServiceSpy).getPublicHolidays(URI);

    BankHolidays cached = publicHolidayService.getPublicHolidays(URI);
    BankHolidays second = publicHolidayService.getPublicHolidays(URI);

    assertThat(second).isSameAs(cached);
    verify(publicHolidayServiceSpy, times(1)).getPublicHolidays(URI);
  }

  @Test
  void shouldRefreshPublicHolidayResponsesAfterCacheExpiry() {
    BankHolidays first = BankHolidays.builder()
        .division("request-1")
        .events(List.of())
        .build();
    BankHolidays refreshed = BankHolidays.builder()
        .division("request-2")
        .events(List.of())
        .build();

    PublicHolidayService publicHolidayServiceSpy = AopTestUtils.getUltimateTargetObject(publicHolidayService);
    doReturn(first, refreshed).when(publicHolidayServiceSpy).getPublicHolidays(URI);

    BankHolidays initial = publicHolidayService.getPublicHolidays(URI);

    calendarTicker.advance(Duration.ofHours(23));
    BankHolidays cached = publicHolidayService.getPublicHolidays(URI);

    calendarTicker.advance(Duration.ofHours(2));
    BankHolidays updated = publicHolidayService.getPublicHolidays(URI);

    assertThat(cached).isSameAs(initial);
    assertThat(updated).isSameAs(refreshed);
    verify(publicHolidayServiceSpy, times(2)).getPublicHolidays(URI);
  }

  @SpringBootConfiguration
  @EnableCaching
  @ImportAutoConfiguration(DelayUntilAutoConfiguration.class)
  static class TestApplication {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean("calendarTicker")
    MutableTicker calendarTicker() {
      return new MutableTicker();
    }
  }

  static class MutableTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }

    void reset() {
      nanos.set(0);
    }
  }
}
