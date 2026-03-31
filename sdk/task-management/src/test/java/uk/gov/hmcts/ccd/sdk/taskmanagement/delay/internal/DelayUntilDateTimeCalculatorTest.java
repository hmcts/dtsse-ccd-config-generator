package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator.DATE_FORMATTER;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

@ExtendWith(MockitoExtension.class)
class DelayUntilDateTimeCalculatorTest {

  public static final LocalDateTime GIVEN_DATE = LocalDateTime.of(2022, 10, 13, 18, 0, 0);
  private DelayUntilDateTimeCalculator delayUntilDateTimeCalculator;

  @BeforeEach
  void before() {
    delayUntilDateTimeCalculator = new DelayUntilDateTimeCalculator();
  }

  @Test
  void should_not_supports_when_responses_contains_delay_until() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(localDateTime + "T16:00")
        .delayUntilTime("14:30")
        .build();

    assertThat(delayUntilDateTimeCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_not_supports_when_responses_contains_delay_until_origin() {
    String localDateTime = GIVEN_DATE.format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntil(localDateTime + "T16:00")
        .delayUntilOrigin(localDateTime + "T20:00")
        .build();

    assertThat(delayUntilDateTimeCalculator.supports(delayUntilRequest)).isFalse();
  }

  @Test
  void should_supports_when_responses_only_contains_delay_until_time() {
    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilTime("16:00")
        .build();

    assertThat(delayUntilDateTimeCalculator.supports(delayUntilRequest)).isTrue();
  }

  @Test
  void should_calculate_delay_until_when_time_is_given() {
    String localDateTime = LocalDateTime.now().format(DATE_FORMATTER);

    DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
        .delayUntilTime("16:00")
        .build();

    LocalDateTime responseValue = delayUntilDateTimeCalculator.calculateDate(delayUntilRequest);
    assertThat(responseValue).isEqualTo(LocalDateTime.parse(localDateTime + "T16:00"));
  }
}
