package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator.DATE_FORMATTER;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

@ExtendWith(MockitoExtension.class)
class DelayUntilDateCalculatorTest {

  public static final LocalDateTime GIVEN_DATE = LocalDateTime.of(2022, 10, 13, 18, 0, 0);

  private DelayUntilDateCalculator delayUntilDateCalculator;

  @BeforeEach
  void before() {
    delayUntilDateCalculator = new DelayUntilDateCalculator();
  }

  @Test
  void should_not_supports_when_responses_contains_delay_until_origin_and_time() {
    String expectedDelayUntil = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilOrigin(expectedDelayUntil + "T16:00")
        .delayUntilTime("16:00")
        .build();

    assertThat(delayUntilDateCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_not_supports_when_responses_contains_only_delay_until_time() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilTime("16:00")
        .build();

    assertThat(delayUntilDateCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_supports_when_responses_only_contains_delay_until_but_not_origin() {
    String expectedDelayUntil = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil + "T16:00")
        .delayUntilTime("16:00")
        .build();

    assertThat(delayUntilDateCalculator.supports(delayUntilRequest)).isTrue();
  }

  @Test
  void should_calculate_delay_until_when_delay_until_is_given() {
    String expectedDelayUntil = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil + "T16:00")
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(GIVEN_DATE.withHour(16));
  }

  @Test
  void should_calculate_delay_until_when_delay_until_and_time_are_given() {
    String expectedDelayUntil = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil + "T16:00")
        .delayUntilTime("20:00")
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(GIVEN_DATE.withHour(20));
  }

  @Test
  void should_calculate_delay_until_when_delay_until_has_full_time() {
    String expectedDelayUntil = GIVEN_DATE.format(DateTimeFormatter.ISO_DATE_TIME);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil)
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(GIVEN_DATE);
  }

  @Test
  void should_calculate_delay_until_when_delay_until_has_full_time_with_mills() {
    String expectedDelayUntil = "2023-04-12T16:45:45.000";
    LocalDateTime givenDate = LocalDateTime.of(2023, 4, 12, 16, 45, 45);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil)
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(givenDate);
  }

  @Test
  void should_calculate_delay_until_when_delay_until_has_full_time_without_seconds() {
    String expectedDelayUntil = "2023-04-12T16:45";
    LocalDateTime givenDate = LocalDateTime.of(2023, 4, 12, 16, 45);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil)
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(givenDate);
  }

  @Test
  void should_calculate_delay_until_when_delay_until_has_full_time_with_nano() {
    String expectedDelayUntil = "2023-04-12T16:45:12.000000123";
    LocalDateTime givenDate = LocalDateTime.of(2023, 4, 12, 16, 45, 12, 123);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil)
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(givenDate);
  }

  @Test
  void should_calculate_delay_until_when_delay_until_has_full_time_with_nano_as_0() {
    String expectedDelayUntil = "2023-04-12T16:45:12";
    LocalDateTime givenDate = LocalDateTime.of(2023, 4, 12, 16, 45, 12, 0);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(expectedDelayUntil)
        .build();

    LocalDateTime dateValue = delayUntilDateCalculator.calculateDate(delayUntilRequest);
    assertThat(dateValue).isEqualTo(givenDate);
  }
}
