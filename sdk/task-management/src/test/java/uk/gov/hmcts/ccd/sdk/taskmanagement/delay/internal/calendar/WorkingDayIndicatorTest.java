package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkingDayIndicatorTest {

  private static final LocalDate BANK_HOLIDAY = toDate("2017-05-29");
  private static final LocalDate NEXT_WORKING_DAY_AFTER_BANK_HOLIDAY = toDate("2017-05-30");
  private static final LocalDate SATURDAY_WEEK_BEFORE = toDate("2017-06-03");
  private static final LocalDate SUNDAY_WEEK_BEFORE = toDate("2017-06-04");
  private static final LocalDate MONDAY = toDate("2017-06-05");
  private static final LocalDate TUESDAY = toDate("2017-06-06");
  private static final LocalDate WEDNESDAY = toDate("2017-06-07");
  private static final LocalDate THURSDAY = toDate("2017-06-08");
  private static final LocalDate FRIDAY = toDate("2017-06-09");
  private static final LocalDate SATURDAY = toDate("2017-06-10");
  private static final LocalDate SUNDAY = toDate("2017-06-11");
  private static final String URI = "http://some-uri.com/calendar";

  private WorkingDayIndicator service;

  @Mock
  private PublicHolidaysCollection publicHolidaysCollection;

  private static LocalDate toDate(String dateString) {
    return LocalDate.parse(dateString, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
  }

  @BeforeEach
  void setup() {
    service = new WorkingDayIndicator(publicHolidaysCollection);
  }

  @Test
  void shouldReturnTrueForWeekdays() {
    when(publicHolidaysCollection.getPublicHolidays(List.of(URI))).thenReturn(Collections.emptySet());

    assertTrue(service.isWorkingDay(MONDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertTrue(service.isWorkingDay(TUESDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertTrue(service.isWorkingDay(WEDNESDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertTrue(service.isWorkingDay(THURSDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertTrue(service.isWorkingDay(FRIDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
  }

  @Test
  void shouldReturnFalseForProvidedNonworkingDays() {
    assertFalse(service.isWorkingDay(SATURDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertFalse(service.isWorkingDay(SUNDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
  }

  @Test
  void shouldReturnTrueWhenNonworkingDaysNotProvided() {
    assertTrue(service.isWorkingDay(SATURDAY, List.of(URI), List.of()));
    assertTrue(service.isWorkingDay(SUNDAY, List.of(URI), List.of()));
  }

  @Test
  void shouldReturnFalseForOneBankHolidayWhenThereIsOneBankHolidayInCollection() {
    LocalDate bankHoliday = BANK_HOLIDAY;
    when(publicHolidaysCollection.getPublicHolidays(List.of(URI)))
        .thenReturn(new HashSet<>(Collections.singletonList(bankHoliday)));

    assertFalse(service.isWorkingDay(bankHoliday, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertTrue(service.isWorkingDay(MONDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
  }

  @Test
  void shouldReturnFalseForPublicHolidayWhenThereIsMoreDatesInPublicHolidaysCollection() {
    Set<LocalDate> publicHolidays = new HashSet<>(Arrays.asList(MONDAY, TUESDAY, WEDNESDAY, THURSDAY));
    when(publicHolidaysCollection.getPublicHolidays(List.of(URI))).thenReturn(publicHolidays);

    assertTrue(service.isWorkingDay(FRIDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));

    assertFalse(service.isWorkingDay(MONDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertFalse(service.isWorkingDay(TUESDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertFalse(service.isWorkingDay(WEDNESDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
    assertFalse(service.isWorkingDay(THURSDAY, List.of(URI), List.of("SATURDAY", "SUNDAY")));
  }

  @Test
  void shouldReturnFollowingMondayForNextWorkingDayGivenASunday() {
    LocalDate nextWorkingDay = service.getNextWorkingDay(
        SUNDAY_WEEK_BEFORE,
        List.of(URI),
        List.of("SATURDAY", "SUNDAY")
    );

    assertEquals(MONDAY, nextWorkingDay);
  }

  @Test
  void shouldReturnPreviousFridayForNextWorkingDayGivenASunday() {
    LocalDate previousWorkingDay = service.getPreviousWorkingDay(
        SUNDAY,
        List.of(URI),
        List.of("SATURDAY", "SUNDAY")
    );

    assertEquals(FRIDAY, previousWorkingDay);
  }

  @Test
  void shouldReturnFollowingMondayForNextWorkingDayGivenASaturday() {
    LocalDate nextWorkingDay = service.getNextWorkingDay(
        SATURDAY_WEEK_BEFORE,
        List.of(URI),
        List.of("SATURDAY", "SUNDAY")
    );

    assertEquals(MONDAY, nextWorkingDay);
  }

  @Test
  void shouldReturnPreviousFridayForNextWorkingDayGivenASaturday() {
    LocalDate previousWorkingDay = service.getPreviousWorkingDay(
        SATURDAY,
        List.of(URI),
        List.of("SATURDAY", "SUNDAY")
    );

    assertEquals(FRIDAY, previousWorkingDay);
  }

  @Test
  void shouldReturnFollowingTuesdayForNextWorkingDayGivenABankHolidayFridayAndMonday() {
    when(publicHolidaysCollection.getPublicHolidays(List.of(URI))).thenReturn(
        new HashSet<>(Collections.singletonList(BANK_HOLIDAY))
    );

    LocalDate nextWorkingDay = service.getNextWorkingDay(
        BANK_HOLIDAY,
        List.of(URI),
        List.of("SATURDAY", "SUNDAY")
    );

    assertEquals(NEXT_WORKING_DAY_AFTER_BANK_HOLIDAY, nextWorkingDay);
  }
}
