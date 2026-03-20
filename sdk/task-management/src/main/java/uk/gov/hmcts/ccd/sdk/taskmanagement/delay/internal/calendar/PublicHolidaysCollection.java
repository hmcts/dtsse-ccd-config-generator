package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import feign.FeignException;
import feign.codec.DecodeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores all public holidays for england and wales retrieved from Gov uk API: https://www.gov.uk/bank-holidays/england-and-wales.json .
 */
@Slf4j
@SuppressWarnings("PMD.DataflowAnomalyAnalysis")
class PublicHolidaysCollection {
  private final PublicHolidayService publicHolidayService;

  PublicHolidaysCollection(PublicHolidayService publicHolidayService) {
    this.publicHolidayService = publicHolidayService;
  }

  Set<LocalDate> getPublicHolidays(List<String> uris) {
    List<BankHolidays.EventDate> events = new ArrayList<>();
    BankHolidays allPublicHolidays = BankHolidays.builder().events(events).build();
    if (uris != null) {
      for (String uri : uris) {
        try {
          BankHolidays publicHolidays = publicHolidayService.getPublicHolidays(uri);
          processCalendar(publicHolidays, allPublicHolidays);
        } catch (DecodeException e) {
          log.error("Could not read calendar resource {}", uri, e);
          throw new CalendarResourceInvalidException("Could not read calendar resource " + uri, e);
        } catch (FeignException e) {
          log.error("Could not find calendar resource {}", uri, e);
          throw new CalendarResourceNotFoundException("Could not find calendar resource " + uri, e);
        }
      }
    }

    return allPublicHolidays.getEvents().stream()
        .map(item -> LocalDate.parse(item.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd")))
        .collect(Collectors.toSet());
  }

  private void processCalendar(BankHolidays publicHolidays, BankHolidays allPublicHolidays) {
    for (BankHolidays.EventDate eventDate : publicHolidays.getEvents()) {
      if (eventDate.isWorkingDay()) {
        if (allPublicHolidays.getEvents().contains(eventDate)) {
          allPublicHolidays.getEvents().remove(eventDate);
        }
      } else {
        allPublicHolidays.getEvents().add(eventDate);
      }
    }
  }
}
