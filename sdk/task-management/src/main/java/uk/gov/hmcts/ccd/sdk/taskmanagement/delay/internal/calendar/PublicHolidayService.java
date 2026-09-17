package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import feign.Feign;
import feign.codec.Decoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;

@Slf4j
class PublicHolidayService {

  private final Decoder feignDecoder;

  PublicHolidayService(Decoder feignDecoder) {
    this.feignDecoder = feignDecoder;
  }

  @Cacheable(value = "calendar_cache", key = "#root.args[0]", sync = true, cacheManager = "calendarCacheManager")
  public BankHolidays getPublicHolidays(String uri) {
    log.info("Getting public holidays for {}", uri);
    BankHolidaysApi bankHolidaysApi = bankHolidaysApi(uri);
    return bankHolidaysApi.retrieveAll();
  }

  private BankHolidaysApi bankHolidaysApi(String uri) {
    return Feign.builder()
      .decoder(feignDecoder)
      .target(BankHolidaysApi.class, uri);
  }
}
