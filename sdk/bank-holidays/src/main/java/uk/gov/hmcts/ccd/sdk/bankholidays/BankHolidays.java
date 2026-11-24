package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The UK's bank holidays as GOV.UK publishes them. The auto-configured bean reads them from the
 * SDK's {@code bank_holidays} schema, which is fetched into daily, so lookups never wait on GOV.UK. It
 * reads them on its first lookup, not when it is created, so an application starts, as it does to
 * generate its CCD definition, without its database.
 *
 * <p>A division's bank holidays are known for the years they are stored for. A date in any other
 * year throws {@link UnknownBankHolidaysException}, rather than being treated as having none.
 */
public class BankHolidays {

  private final Supplier<? extends Collection<BankHoliday>> load;
  private Map<Division, TreeMap<LocalDate, BankHoliday>> holidays;

  /** Bank holidays read with the given load on the first lookup, and again on each reload. */
  BankHolidays(Supplier<? extends Collection<BankHoliday>> load) {
    this.load = Objects.requireNonNull(load, "load");
  }

  /** Bank holidays fixed to the given ones, for tests. */
  public static BankHolidays of(Collection<BankHoliday> holidays) {
    List<BankHoliday> fixed = List.copyOf(holidays);
    return new BankHolidays(() -> fixed);
  }

  /** The copy of GOV.UK's bank holidays in this version of the SDK, for tests. */
  public static BankHolidays bundled() {
    return of(GovUkBankHolidays.seed());
  }

  /** Whether the date is one of the division's bank holidays. Weekends are not, unless GOV.UK lists them. */
  public boolean isBankHoliday(Division division, LocalDate date) {
    TreeMap<LocalDate, BankHoliday> dates = byDivision().get(Objects.requireNonNull(division, "division"));
    int year = date.getYear();
    if (dates.subMap(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1)).isEmpty()) {
      throw new UnknownBankHolidaysException(division + " bank holidays are not known for " + year);
    }
    return dates.containsKey(date);
  }

  /** All the division's bank holidays known, earliest first. */
  public List<BankHoliday> holidays(Division division) {
    return List.copyOf(byDivision().get(Objects.requireNonNull(division, "division")).values());
  }

  synchronized void reload() {
    Map<Division, TreeMap<LocalDate, BankHoliday>> byDivision = new EnumMap<>(Division.class);
    for (Division division : Division.values()) {
      byDivision.put(division, new TreeMap<>());
    }
    load.get().forEach(holiday -> byDivision.get(holiday.division()).put(holiday.date(), holiday));
    holidays = byDivision;
  }

  // Under the lock reload holds, so an older read never replaces a newer one.
  private synchronized Map<Division, TreeMap<LocalDate, BankHoliday>> byDivision() {
    if (holidays == null) {
      reload();
    }
    return holidays;
  }
}
