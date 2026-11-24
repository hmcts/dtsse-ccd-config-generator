package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The UK's bank holidays as GOV.UK publishes them, and each division's working days by them.
 *
 * <p>The auto-configured bean reads them from the SDK's {@code bank_holidays} schema, which is
 * refreshed from GOV.UK in the background and seeded from a copy in the SDK, so lookups never wait
 * on GOV.UK.
 */
public class BankHolidays {

  private volatile Map<Division, WorkingDayCalendar> calendars;

  BankHolidays(Collection<BankHoliday> holidays) {
    replace(holidays);
  }

  /**
   * Bank holidays fixed to the given ones, for tests. Each division's are known for the whole years
   * from its earliest given holiday to its latest; a division given none has none known.
   *
   * @throws IllegalArgumentException if a division is given the same date twice
   */
  public static BankHolidays of(Collection<BankHoliday> holidays) {
    return new BankHolidays(holidays);
  }

  /** The copy of GOV.UK's bank holidays in this version of the SDK, without a database, for tests. */
  public static BankHolidays bundled() {
    return of(GovUkBankHolidays.seed());
  }

  /** The division's working days as they are now, to calculate with. */
  public WorkingDayCalendar calendar(Division division) {
    return calendars.get(Objects.requireNonNull(division, "division"));
  }

  void replace(Collection<BankHoliday> holidays) {
    Map<Division, NavigableMap<LocalDate, BankHoliday>> byDivision = new EnumMap<>(Division.class);
    for (Division division : Division.values()) {
      byDivision.put(division, new TreeMap<>());
    }
    for (BankHoliday holiday : holidays) {
      if (byDivision.get(holiday.division()).putIfAbsent(holiday.date(), holiday) != null) {
        throw new IllegalArgumentException(holiday.division() + " has more than one bank holiday on "
            + holiday.date());
      }
    }
    Map<Division, WorkingDayCalendar> replaced = new EnumMap<>(Division.class);
    byDivision.forEach((division, dates) ->
        replaced.put(division, new WorkingDayCalendar(division, Collections.unmodifiableNavigableMap(dates))));
    calendars = replaced;
  }
}
