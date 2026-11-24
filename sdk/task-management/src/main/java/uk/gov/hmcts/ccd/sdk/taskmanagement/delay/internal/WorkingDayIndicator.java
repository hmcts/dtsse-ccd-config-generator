package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidays;
import uk.gov.hmcts.ccd.sdk.bankholidays.Division;

/**
 * Working days by the non-working calendars and days of the week a delay names, as Task Management
 * takes them. A calendar is GOV.UK's bank holidays URL for a division, such as
 * {@code https://www.gov.uk/bank-holidays/england-and-wales.json}; its bank holidays come from
 * {@link BankHolidays} rather than from the URL.
 */
@RequiredArgsConstructor
class WorkingDayIndicator {

  private final BankHolidays bankHolidays;

  WorkingDays of(List<String> calendars, List<String> nonWorkingDaysOfWeek) {
    return new WorkingDays(calendars.stream().map(WorkingDayIndicator::division).toList(), nonWorkingDaysOfWeek);
  }

  class WorkingDays {

    private final List<Division> divisions;
    private final List<String> nonWorkingDaysOfWeek;

    WorkingDays(List<Division> divisions, List<String> nonWorkingDaysOfWeek) {
      this.divisions = divisions;
      this.nonWorkingDaysOfWeek = nonWorkingDaysOfWeek;
    }

    boolean isWorkingDay(LocalDate date) {
      return !nonWorkingDaysOfWeek.contains(date.getDayOfWeek().toString())
          && divisions.stream().noneMatch(division -> bankHolidays.isBankHoliday(division, date));
    }

    LocalDate next(LocalDate date) {
      return step(date, 1);
    }

    LocalDate previous(LocalDate date) {
      return step(date, -1);
    }

    private LocalDate step(LocalDate date, int days) {
      LocalDate result = date.plusDays(days);
      while (!isWorkingDay(result)) {
        result = result.plusDays(days);
      }
      return result;
    }
  }

  private static Division division(String calendar) {
    return Arrays.stream(Division.values())
        .filter(division -> calendar.equals("https://www.gov.uk/bank-holidays/" + division.govUkName() + ".json"))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported non-working calendar " + calendar
            + "; use GOV.UK's bank holidays for a division, such as "
            + DelayUntilCalculator.DEFAULT_NON_WORKING_CALENDAR));
  }
}
