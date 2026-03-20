package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator.DATE_FORMATTER;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NEXT;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NO;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_PREVIOUS;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar.WorkingDayIndicator;

@ExtendWith(MockitoExtension.class)
class DelayUntilIntervalCalculatorTest {

  public static final String CALENDAR_URI = "https://www.gov.uk/bank-holidays/england-and-wales.json";
  public static final LocalDateTime GIVEN_DATE = LocalDateTime.of(2022, 10, 13, 18, 0, 0);

  private Object publicHolidaysCollection;
  private DelayUntilIntervalCalculator delayUntilIntervalCalculator;

  @BeforeEach
  void before() {
    publicHolidaysCollection = mockPublicHolidaysCollection();
    delayUntilIntervalCalculator = new DelayUntilIntervalCalculator(newWorkingDayIndicator(publicHolidaysCollection));

    Set<LocalDate> localDates = Set.of(
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
    );

    lenient().when(invokeGetPublicHolidays(List.of(CALENDAR_URI))).thenReturn(localDates);
  }

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
    when(invokeGetPublicHolidays(List.of(CALENDAR_URI)))
        .thenReturn(Set.of(LocalDate.of(2022, 10, 18)));

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

  @SuppressWarnings("unchecked")
  private Set<LocalDate> invokeGetPublicHolidays(List<String> uris) {
    try {
      Method method = publicHolidaysCollectionClass().getDeclaredMethod("getPublicHolidays", List.class);
      method.setAccessible(true);
      return (Set<LocalDate>) method.invoke(publicHolidaysCollection, uris);
    } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException
             | NoSuchMethodException exception) {
      throw new RuntimeException(exception);
    }
  }

  private Object mockPublicHolidaysCollection() {
    try {
      return org.mockito.Mockito.mock(publicHolidaysCollectionClass());
    } catch (ClassNotFoundException exception) {
      throw new RuntimeException(exception);
    }
  }

  private WorkingDayIndicator newWorkingDayIndicator(Object collection) {
    try {
      Constructor<WorkingDayIndicator> constructor =
          WorkingDayIndicator.class.getDeclaredConstructor(publicHolidaysCollectionClass());
      constructor.setAccessible(true);
      return constructor.newInstance(collection);
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
             | InvocationTargetException | NoSuchMethodException exception) {
      throw new RuntimeException(exception);
    }
  }

  private Class<?> publicHolidaysCollectionClass() throws ClassNotFoundException {
    return Class.forName("uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar.PublicHolidaysCollection");
  }
}
