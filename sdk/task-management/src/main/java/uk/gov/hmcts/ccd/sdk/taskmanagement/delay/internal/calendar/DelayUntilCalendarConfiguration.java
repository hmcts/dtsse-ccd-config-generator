package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(SnakeCaseFeignConfiguration.class)
public class DelayUntilCalendarConfiguration {

  private static final Duration CALENDAR_CACHE_TTL = Duration.ofHours(24);

  @Bean
  @ConditionalOnMissingBean
  WorkingDayIndicator workingDayIndicator(PublicHolidaysCollection publicHolidaysCollection) {
    return new WorkingDayIndicator(publicHolidaysCollection);
  }

  @Bean
  @ConditionalOnMissingBean
  PublicHolidaysCollection publicHolidaysCollection(PublicHolidayService publicHolidayService) {
    return new PublicHolidaysCollection(publicHolidayService);
  }

  @Bean
  @ConditionalOnMissingBean
  PublicHolidayService publicHolidayService(
      @Qualifier("calendarFeignDecoder") Decoder calendarFeignDecoder,
      @Qualifier("calendarFeignEncoder") Encoder calendarFeignEncoder
  ) {
    return new PublicHolidayService(calendarFeignDecoder, calendarFeignEncoder);
  }

  @Bean("calendarCacheManager")
  @ConditionalOnMissingBean(name = "calendarCacheManager")
  CacheManager calendarCacheManager(@Qualifier("calendarTicker") Ticker ticker) {
    CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
    caffeineCacheManager.setCacheNames(List.of("calendar_cache"));
    caffeineCacheManager.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(CALENDAR_CACHE_TTL)
            .ticker(ticker)
    );
    return caffeineCacheManager;
  }

  @Bean("calendarTicker")
  @ConditionalOnMissingBean(name = "calendarTicker")
  Ticker calendarTicker() {
    return Ticker.systemTicker();
  }
}
