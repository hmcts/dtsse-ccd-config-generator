package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The UK's bank holidays as GOV.UK publishes them, and each division's working days by them.
 *
 * <p>The auto-configured bean reads them from the SDK's {@code bank_holidays} schema, which is
 * refreshed from GOV.UK in the background and seeded from a copy in the SDK, so lookups never wait
 * on GOV.UK.
 */
public class BankHolidays {

  private final Supplier<? extends Collection<BankHoliday>> source;
  private volatile Map<Division, WorkingDayCalendar> calendars;

  BankHolidays(Supplier<? extends Collection<BankHoliday>> source) {
    this.source = source;
  }

  /** Bank holidays fixed to the given ones, for tests. */
  public static BankHolidays of(Collection<BankHoliday> holidays) {
    List<BankHoliday> fixed = List.copyOf(holidays);
    return new BankHolidays(() -> fixed);
  }

  /** The division's working days as they are now, to calculate with. */
  public WorkingDayCalendar calendar(Division division) {
    return calendars().get(Objects.requireNonNull(division, "division"));
  }

  /**
   * The division's bank holidays from the first date to before the second, earliest first.
   *
   * @throws UnknownBankHolidaysException if the dates reach a year GOV.UK has not published
   */
  public List<BankHoliday> holidays(Division division, LocalDate from, LocalDate toExclusive) {
    return calendar(division).between(from, toExclusive);
  }

  /** Reads the bank holidays again from their source. */
  void reload() {
    Map<Division, NavigableMap<LocalDate, BankHoliday>> byDivision = new EnumMap<>(Division.class);
    for (Division division : Division.values()) {
      byDivision.put(division, new TreeMap<>());
    }
    source.get().forEach(holiday -> byDivision.get(holiday.division()).put(holiday.date(), holiday));
    Map<Division, WorkingDayCalendar> loaded = new EnumMap<>(Division.class);
    byDivision.forEach((division, holidays) ->
        loaded.put(division, new WorkingDayCalendar(division, Collections.unmodifiableNavigableMap(holidays))));
    calendars = loaded;
  }

  private Map<Division, WorkingDayCalendar> calendars() {
    Map<Division, WorkingDayCalendar> current = calendars;
    if (current == null) {
      synchronized (this) {
        if (calendars == null) {
          reload();
        }
        current = calendars;
      }
    }
    return current;
  }
}
