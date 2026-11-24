package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The UK's bank holidays as GOV.UK publishes them. The auto-configured bean reads them from the
 * SDK's {@code bank_holidays} schema, which is fetched into daily, so lookups never wait on GOV.UK.
 *
 * <p>A division's bank holidays are known for the years they are stored for. A date in any other
 * year throws {@link UnknownBankHolidaysException}, rather than being treated as having none.
 */
public class BankHolidays {

  private volatile Map<Division, TreeMap<LocalDate, BankHoliday>> holidays;

  BankHolidays(Collection<BankHoliday> holidays) {
    replace(holidays);
  }

  /** Bank holidays fixed to the given ones, for tests. */
  public static BankHolidays of(Collection<BankHoliday> holidays) {
    return new BankHolidays(holidays);
  }

  /** The copy of GOV.UK's bank holidays in this version of the SDK, for tests. */
  public static BankHolidays bundled() {
    return of(GovUkBankHolidays.seed());
  }

  /** Whether the date is one of the division's bank holidays. Weekends are not, unless GOV.UK lists them. */
  public boolean isBankHoliday(Division division, LocalDate date) {
    TreeMap<LocalDate, BankHoliday> dates = holidays.get(Objects.requireNonNull(division, "division"));
    int year = date.getYear();
    if (dates.subMap(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1)).isEmpty()) {
      throw new UnknownBankHolidaysException(division + " bank holidays are not known for " + year);
    }
    return dates.containsKey(date);
  }

  /** All the division's bank holidays known, earliest first. */
  public List<BankHoliday> holidays(Division division) {
    return List.copyOf(holidays.get(Objects.requireNonNull(division, "division")).values());
  }

  void replace(Collection<BankHoliday> replacement) {
    Map<Division, TreeMap<LocalDate, BankHoliday>> byDivision = new EnumMap<>(Division.class);
    for (Division division : Division.values()) {
      byDivision.put(division, new TreeMap<>());
    }
    replacement.forEach(holiday -> byDivision.get(holiday.division()).put(holiday.date(), holiday));
    holidays = byDivision;
  }
}
