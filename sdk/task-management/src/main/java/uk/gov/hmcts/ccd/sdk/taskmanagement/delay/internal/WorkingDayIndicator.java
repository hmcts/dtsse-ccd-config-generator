package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidays;
import uk.gov.hmcts.ccd.sdk.bankholidays.Division;
import uk.gov.hmcts.ccd.sdk.bankholidays.WorkingDayCalendar;

/**
 * Working days by the non-working calendars and days of the week a delay names, as Task Management
 * takes them. A calendar is GOV.UK's bank holidays URL for a division, such as
 * {@code https://www.gov.uk/bank-holidays/england-and-wales.json}; its bank holidays come from
 * {@link BankHolidays} rather than from the URL.
 */
@RequiredArgsConstructor
class WorkingDayIndicator {

  private static final Pattern GOV_UK_CALENDAR =
      Pattern.compile("https://www\\.gov\\.uk/bank-holidays/([a-z-]+)\\.json");

  private final BankHolidays bankHolidays;

  /** The working days for one delay, its calendars taken once so that it is worked out with one set. */
  WorkingDays of(List<String> calendars, List<String> nonWorkingDaysOfWeek) {
    return new WorkingDays(calendars.stream().map(calendar -> bankHolidays.calendar(division(calendar))).toList(),
        nonWorkingDaysOfWeek);
  }

  record WorkingDays(List<WorkingDayCalendar> calendars, List<String> nonWorkingDaysOfWeek) {

    boolean isWorkingDay(LocalDate date) {
      return !nonWorkingDaysOfWeek.contains(date.getDayOfWeek().toString())
          && calendars.stream().noneMatch(calendar -> calendar.isBankHoliday(date));
    }

    LocalDate next(LocalDate date) {
      LocalDate next = requireNonNull(date).plusDays(1);
      while (!isWorkingDay(next)) {
        next = next.plusDays(1);
      }
      return next;
    }

    LocalDate previous(LocalDate date) {
      LocalDate previous = requireNonNull(date).minusDays(1);
      while (!isWorkingDay(previous)) {
        previous = previous.minusDays(1);
      }
      return previous;
    }
  }

  private static Division division(String calendar) {
    Matcher matcher = GOV_UK_CALENDAR.matcher(calendar);
    return (matcher.matches() ? Division.fromGovUkName(matcher.group(1)) : Optional.<Division>empty())
        .orElseThrow(() -> new IllegalArgumentException("Unsupported non-working calendar " + calendar
            + "; use GOV.UK's bank holidays for a division, such as "
            + DelayUntilCalculator.DEFAULT_NON_WORKING_CALENDAR));
  }
}
