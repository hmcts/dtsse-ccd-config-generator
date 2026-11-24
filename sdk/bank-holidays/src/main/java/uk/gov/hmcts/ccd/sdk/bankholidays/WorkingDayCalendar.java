package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * A division's working days, Monday to Friday except its bank holidays, as they were when the
 * calendar was taken: immutable, so a calculation made with it is not changed by a refresh.
 *
 * <p>A division's bank holidays are known for the whole years GOV.UK has published. Every method
 * throws {@link UnknownBankHolidaysException} for a date outside them, rather than treat it as having
 * none.
 */
public final class WorkingDayCalendar {

  private final Division division;
  private final NavigableMap<LocalDate, BankHoliday> holidays;

  WorkingDayCalendar(Division division, NavigableMap<LocalDate, BankHoliday> holidays) {
    this.division = division;
    this.holidays = holidays;
  }

  public Division division() {
    return division;
  }

  /** Whether the date is one of the division's bank holidays. */
  public boolean isHoliday(LocalDate date) {
    requireKnown(date);
    return holidays.containsKey(date);
  }

  /** Whether the date is a working day: Monday to Friday and not a bank holiday. */
  public boolean isWorkingDay(LocalDate date) {
    return !isWeekend(date) && !isHoliday(date);
  }

  /**
   * The date the given number of working days after the date, or before it when negative, counting
   * from the date whether or not it is a working day. Zero gives the date itself.
   */
  public LocalDate shift(LocalDate date, int workingDays) {
    requireKnown(date);
    int step = workingDays < 0 ? -1 : 1;
    LocalDate result = date;
    for (int remaining = Math.abs(workingDays); remaining > 0; ) {
      result = result.plusDays(step);
      if (isWorkingDay(result)) {
        remaining--;
      }
    }
    return result;
  }

  /** The date if it is a working day, otherwise the next working day after it. */
  public LocalDate nextOrSame(LocalDate date) {
    LocalDate result = date;
    while (!isWorkingDay(result)) {
      result = result.plusDays(1);
    }
    return result;
  }

  /** The date if it is a working day, otherwise the last working day before it. */
  public LocalDate previousOrSame(LocalDate date) {
    LocalDate result = date;
    while (!isWorkingDay(result)) {
      result = result.minusDays(1);
    }
    return result;
  }

  /** The last date the division's bank holidays are known for: 31 December of its last year. */
  public LocalDate knownUntil() {
    requireAny();
    return holidays.lastKey().withMonth(12).withDayOfMonth(31);
  }

  List<BankHoliday> between(LocalDate from, LocalDate toExclusive) {
    if (!from.isBefore(toExclusive)) {
      return List.of();
    }
    requireKnown(from);
    requireKnown(toExclusive.minusDays(1));
    return List.copyOf(holidays.subMap(from, true, toExclusive, false).values());
  }

  private LocalDate knownFrom() {
    requireAny();
    return holidays.firstKey().withDayOfYear(1);
  }

  private void requireKnown(LocalDate date) {
    Objects.requireNonNull(date, "date");
    if (date.isBefore(knownFrom()) || date.isAfter(knownUntil())) {
      throw new UnknownBankHolidaysException(division + " bank holidays are not known for " + date
          + "; they are known from " + knownFrom() + " to " + knownUntil());
    }
  }

  private void requireAny() {
    if (holidays.isEmpty()) {
      throw new UnknownBankHolidaysException("No " + division + " bank holidays are known");
    }
  }

  private static boolean isWeekend(LocalDate date) {
    DayOfWeek day = date.getDayOfWeek();
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
  }
}
