package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import static java.util.Objects.requireNonNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public class WorkingDayIndicator {

  private final PublicHolidaysCollection publicHolidaysCollection;

  WorkingDayIndicator(PublicHolidaysCollection publicHolidaysApiClient) {
    this.publicHolidaysCollection = publicHolidaysApiClient;
  }

  /**
   * Verifies if given date is a working day in UK (England and Wales only).
   */
  public boolean isWorkingDay(LocalDate date, List<String> uri, List<String> nonWorkingDaysOfWeek) {
    return !isPublicHoliday(date, uri)
        && !isCustomNonWorkingDay(nonWorkingDaysOfWeek, date);
  }

  private boolean isPublicHoliday(LocalDate date, List<String> uri) {
    return publicHolidaysCollection.getPublicHolidays(uri).contains(date);
  }

  public LocalDate getNextWorkingDay(LocalDate date, List<String> uri, List<String> nonWorkingDaysOfWeek) {
    requireNonNull(date);
    LocalDate updated = date.plusDays(1);

    return isWorkingDay(updated, uri, nonWorkingDaysOfWeek)
        ? updated
        : getNextWorkingDay(updated, uri, nonWorkingDaysOfWeek);
  }

  public LocalDate getPreviousWorkingDay(LocalDate date, List<String> uri, List<String> nonWorkingDaysOfWeek) {
    requireNonNull(date);
    LocalDate updated = date.minusDays(1);

    return isWorkingDay(updated, uri, nonWorkingDaysOfWeek)
        ? updated
        : getPreviousWorkingDay(updated, uri, nonWorkingDaysOfWeek);
  }

  private boolean isCustomNonWorkingDay(List<String> nonWorkingDaysOfWeek, LocalDate localDate) {
    if (nonWorkingDaysOfWeek == null || nonWorkingDaysOfWeek.isEmpty()) {
      return false;
    }
    DayOfWeek dayOfWeek = localDate.getDayOfWeek();
    return nonWorkingDaysOfWeek.contains(dayOfWeek.toString());
  }
}
