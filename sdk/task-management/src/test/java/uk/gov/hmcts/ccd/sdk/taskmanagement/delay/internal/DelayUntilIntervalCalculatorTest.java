package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator.DATE_FORMATTER;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NEXT;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NO;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_PREVIOUS;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHoliday;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidays;
import uk.gov.hmcts.ccd.sdk.bankholidays.Division;
import uk.gov.hmcts.ccd.sdk.bankholidays.UnknownBankHolidaysException;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

class DelayUntilIntervalCalculatorTest {

  public static final String CALENDAR_URI = "https://www.gov.uk/bank-holidays/england-and-wales.json";
  public static final LocalDateTime GIVEN_DATE = LocalDateTime.of(2022, 10, 13, 18, 0, 0);

  // England and Wales's bank holidays in 2022.
  private static final LocalDate[] BANK_HOLIDAYS_2022 = {
      LocalDate.of(2022, 1, 3),
      LocalDate.of(2022, 4, 15),
      LocalDate.of(2022, 4, 18),
      LocalDate.of(2022, 5, 2),
      LocalDate.of(2022, 6, 2),
      LocalDate.of(2022, 6, 3),
      LocalDate.of(2022, 8, 29),
      LocalDate.of(2022, 9, 19),
      LocalDate.of(2022, 12, 26),
      LocalDate.of(2022, 12, 27)
  };

  private final DelayUntilIntervalCalculator delayUntilIntervalCalculator = calculator(BANK_HOLIDAYS_2022);

  @Test
  void shouldCalculateWhenDefaultValueProvided() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(0)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("")
        .delayUntilSkipNonWorkingDays(true)
        .delayUntilMustBeWorkingDay("No")
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);
    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(0).withHour(18));
  }

  @Test
  void shouldCalculateWhenIntervalIsGreaterThan0() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(3)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("")
        .delayUntilSkipNonWorkingDays(true)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NEXT)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(3).withHour(18));
  }

  @Test
  void shouldCalculateWhenIntervalIsLessThan0() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(-3)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("")
        .delayUntilSkipNonWorkingDays(true)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NEXT)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(-3).withHour(18));
  }

  @Test
  void shouldCalculateWhenIntervalIsLessThan0AndGivenHolidays() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(-5)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilSkipNonWorkingDays(true)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NEXT)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(-7).withHour(18));
  }

  @Test
  void shouldCalculateWhenSkipNonWorkingDaysFalse() {
    DelayUntilIntervalCalculator delayUntilIntervalCalculator = calculator(LocalDate.of(2022, 10, 18));

    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(5)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilSkipNonWorkingDays(false)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NEXT)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(6).withHour(18));
  }

  @Test
  void shouldCalculateWhenSkipNonWorkingDaysAndMustBeBusinessNext() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(2)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilSkipNonWorkingDays(false)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NEXT)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(4).withHour(18));
  }

  @Test
  void shouldCalculateWhenSkipNonWorkingDaysAndMustBeBusinessFalse() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(2)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilSkipNonWorkingDays(false)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_PREVIOUS)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(1).withHour(18));
  }

  @Test
  void shouldCalculateWhenWithoutDelayUntilTime() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilIntervalDays(5)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilSkipNonWorkingDays(false)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NO)
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.plusDays(5).withHour(18));
  }

  @Test
  void shouldCalculateWhenOnlyDelayUntilOriginProvided() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.withHour(20));
  }

  @Test
  void shouldCalculateWhenOnlyDelayUntilOriginAndTimeProvided() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(localDateTime + "T20:00")
        .delayUntilTime("18:00")
        .build();

    LocalDateTime delayUntilDate = delayUntilIntervalCalculator.calculateDate(delayUntilRequest);

    assertThat(delayUntilDate).isEqualTo(GIVEN_DATE.withHour(18));
  }

  @Test
  void should_not_supports_when_responses_contains_delay_until_origin_and_delay_until() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(localDateTime + "T16:00")
        .delayUntilOrigin(localDateTime + "T16:00")
        .build();

    assertThat(delayUntilIntervalCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_not_supports_when_responses_contains_only_delay_until_time() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilTime("T16:00")
        .build();

    assertThat(delayUntilIntervalCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_supports_when_responses_only_contains_delay_until_origin_but_not_delay_until() {
    String expectedDelayUntil = GIVEN_DATE.format(DATE_FORMATTER);
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(expectedDelayUntil + "T16:00")
        .delayUntilTime("16:00")
        .build();

    assertThat(delayUntilIntervalCalculator.supports(delayUntilRequest)).isTrue();
  }

  @Test
  void shouldTakeBankHolidaysForTheDivisionTheCalendarNames() {
    DelayUntilIntervalCalculator scottish = new DelayUntilIntervalCalculator(new WorkingDayIndicator(
        BankHolidays.of(List.of(new BankHoliday(Division.SCOTLAND, LocalDate.of(2022, 10, 14), "A holiday", "")))));

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(GIVEN_DATE.format(DATE_FORMATTER) + "T20:00")
        .delayUntilIntervalDays(1)
        .delayUntilNonWorkingCalendar("https://www.gov.uk/bank-holidays/scotland.json")
        .delayUntilNonWorkingDaysOfWeek("SATURDAY,SUNDAY")
        .delayUntilTime("18:00")
        .build();

    assertThat(scottish.calculateDate(delayUntilRequest)).isEqualTo(GIVEN_DATE.plusDays(4).withHour(18));
  }

  @Test
  void shouldRefuseCalendarsOtherThanGovUksBankHolidays() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(GIVEN_DATE.format(DATE_FORMATTER) + "T20:00")
        .delayUntilIntervalDays(1)
        .delayUntilNonWorkingCalendar("https://example.com/court-closures.json")
        .build();

    assertThatThrownBy(() -> delayUntilIntervalCalculator.calculateDate(delayUntilRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported non-working calendar https://example.com/court-closures.json");
  }

  @Test
  void shouldRefuseDatesInYearsWithoutKnownBankHolidays() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin("2022-12-30T20:00")
        .delayUntilIntervalDays(3)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .build();

    assertThatThrownBy(() -> delayUntilIntervalCalculator.calculateDate(delayUntilRequest))
        .isInstanceOf(UnknownBankHolidaysException.class);
  }

  @Test
  void shouldNotNeedBankHolidaysForCalendarDaysThatNeedNotBeWorkingDays() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin("2030-01-01T20:00")
        .delayUntilIntervalDays(3)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilSkipNonWorkingDays(false)
        .delayUntilMustBeWorkingDay(MUST_BE_WORKING_DAY_NO)
        .build();

    assertThat(delayUntilIntervalCalculator.calculateDate(delayUntilRequest))
        .isEqualTo(LocalDateTime.of(2030, 1, 4, 20, 0));
  }

  @Test
  void shouldRefuseEveryDayOfTheWeekBeingNonWorking() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(GIVEN_DATE.format(DATE_FORMATTER) + "T20:00")
        .delayUntilIntervalDays(1)
        .delayUntilNonWorkingCalendar(CALENDAR_URI)
        .delayUntilNonWorkingDaysOfWeek("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")
        .build();

    assertThatThrownBy(() -> delayUntilIntervalCalculator.calculateDate(delayUntilRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Every day of the week is non-working");
  }

  private static DelayUntilIntervalCalculator calculator(LocalDate... bankHolidays) {
    return new DelayUntilIntervalCalculator(new WorkingDayIndicator(BankHolidays.of(Stream.concat(
            Arrays.stream(BANK_HOLIDAYS_2022), Arrays.stream(bankHolidays))
        .distinct()
        .map(date -> new BankHoliday(Division.ENGLAND_AND_WALES, date, "Bank holiday", ""))
        .toList())));
  }
}
