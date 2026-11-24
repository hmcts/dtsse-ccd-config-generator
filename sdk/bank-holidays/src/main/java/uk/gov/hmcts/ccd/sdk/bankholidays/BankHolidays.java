package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The UK's bank holidays as GOV.UK publishes them, and working days by them.
 *
 * <p>The auto-configured bean reads them from the SDK's {@code bank_holidays} schema, which is
 * refreshed from GOV.UK in the background and seeded from a copy in the SDK, so lookups never wait
 * on GOV.UK. A division's bank holidays are known for the whole years GOV.UK has published; every
 * method throws {@link UnknownBankHolidaysException} for a date outside them rather than treat it as
 * having none.
 */
public class BankHolidays {

  private final Supplier<? extends Collection<BankHoliday>> source;
  private volatile Calendar calendar;

  BankHolidays(Supplier<? extends Collection<BankHoliday>> source) {
    this.source = source;
  }

  /** Bank holidays fixed to the given ones, for tests. */
  public static BankHolidays of(Collection<BankHoliday> holidays) {
    List<BankHoliday> fixed = List.copyOf(holidays);
    return new BankHolidays(() -> fixed);
  }

  /** Whether the date is a bank holiday in the division. */
  public boolean isBankHoliday(Division division, LocalDate date) {
    return calendar().of(division).isBankHoliday(date);
  }

  /** Whether the date is a working day in the division: Monday to Friday and not a bank holiday. */
  public boolean isWorkingDay(Division division, LocalDate date) {
    return !isWeekend(date) && !isBankHoliday(division, date);
  }

  /**
   * The date the given number of working days after the date, or before it when negative. Zero
   * gives the date itself, whether or not it is a working day.
   */
  public LocalDate addWorkingDays(Division division, LocalDate date, int workingDays) {
    Objects.requireNonNull(date, "date");
    int step = workingDays < 0 ? -1 : 1;
    LocalDate result = date;
    for (int remaining = Math.abs(workingDays); remaining > 0; ) {
      result = result.plusDays(step);
      if (isWorkingDay(division, result)) {
        remaining--;
      }
    }
    return result;
  }

  /** The division's bank holidays from the first date to before the second, earliest first. */
  public List<BankHoliday> between(Division division, LocalDate from, LocalDate toExclusive) {
    if (!from.isBefore(toExclusive)) {
      return List.of();
    }
    Calendar.Holidays holidays = calendar().of(division);
    holidays.requireKnown(from);
    holidays.requireKnown(toExclusive.minusDays(1));
    return List.copyOf(holidays.dates.subMap(from, true, toExclusive, false).values());
  }

  /** The first date the division's bank holidays are known for: 1 January of its first year. */
  public LocalDate knownFrom(Division division) {
    return calendar().of(division).knownFrom();
  }

  /** The last date the division's bank holidays are known for: 31 December of its last year. */
  public LocalDate knownUntil(Division division) {
    return calendar().of(division).knownUntil();
  }

  /** Reads the bank holidays again from their source. */
  void reload() {
    calendar = new Calendar(source.get());
  }

  private Calendar calendar() {
    Calendar current = calendar;
    if (current == null) {
      synchronized (this) {
        if (calendar == null) {
          reload();
        }
        current = calendar;
      }
    }
    return current;
  }

  private static boolean isWeekend(LocalDate date) {
    DayOfWeek day = date.getDayOfWeek();
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
  }

  private static final class Calendar {

    private final Map<Division, Holidays> divisions = new EnumMap<>(Division.class);

    Calendar(Collection<BankHoliday> holidays) {
      Map<Division, NavigableMap<LocalDate, BankHoliday>> byDivision = new EnumMap<>(Division.class);
      holidays.forEach(holiday -> byDivision
          .computeIfAbsent(holiday.division(), division -> new TreeMap<>())
          .put(holiday.date(), holiday));
      for (Division division : Division.values()) {
        divisions.put(division, new Holidays(division, byDivision.getOrDefault(division, new TreeMap<>())));
      }
    }

    Holidays of(Division division) {
      return divisions.get(Objects.requireNonNull(division, "division"));
    }

    private record Holidays(Division division, NavigableMap<LocalDate, BankHoliday> dates) {

      boolean isBankHoliday(LocalDate date) {
        requireKnown(date);
        return dates.containsKey(date);
      }

      void requireKnown(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(knownFrom()) || date.isAfter(knownUntil())) {
          throw new UnknownBankHolidaysException(division + " bank holidays are not known for " + date
              + "; they are known from " + knownFrom() + " to " + knownUntil());
        }
      }

      LocalDate knownFrom() {
        requireAny();
        return dates.firstKey().withDayOfYear(1);
      }

      LocalDate knownUntil() {
        requireAny();
        return dates.lastKey().withMonth(12).withDayOfMonth(31);
      }

      private void requireAny() {
        if (dates.isEmpty()) {
          throw new UnknownBankHolidaysException("No " + division + " bank holidays are known");
        }
      }
    }
  }
}
