package uk.gov.hmcts.ccd.sdk.taskmanagement.delay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator;

@ExtendWith(MockitoExtension.class)
class DelayUntilResolverTest {

  private static final LocalDateTime GIVEN_DATE = LocalDateTime.of(2022, 10, 13, 16, 0, 0);

  private DelayUntilResolver delayUntilResolver;

  @Nested
  class DefaultWithoutAnyDateCalculator {

    @Test
    void should_return_default_calculated_dates_when_there_are_no_dmn_responses() {
      delayUntilResolver = new DelayUntilResolver(List.of());

      LocalDateTime calculateDelayUntil = delayUntilResolver.resolve(
          DelayUntilRequest.builder().build()
      );

      assertThat(calculateDelayUntil).isCloseTo(LocalDateTime.now(), within(100, ChronoUnit.SECONDS));
    }

    @Test
    void should_set_date_as_current_day_when_delay_until_is_given() {
      delayUntilResolver = new DelayUntilResolver(List.of());

      LocalDateTime calculateDelayUntil = delayUntilResolver.resolve(
          DelayUntilRequest.builder().delayUntil("2023-01-10T16:00").build()
      );

      assertThat(calculateDelayUntil).isCloseTo(LocalDateTime.now(), within(100, ChronoUnit.SECONDS));
    }
  }

  @Nested
  class DefaultWithDateCalculators {

    @Mock
    private DelayUntilCalculator unsupportedCalculator;

    @Mock
    private DelayUntilCalculator supportedCalculator;

    @Test
    void should_use_the_first_supporting_calculator() {
      DelayUntilRequest delayUntilRequest = DelayUntilRequest.builder()
          .delayUntil("2022-10-13T16:00")
          .build();

      delayUntilResolver = new DelayUntilResolver(List.of(unsupportedCalculator, supportedCalculator));

      when(unsupportedCalculator.supports(delayUntilRequest)).thenReturn(false);
      when(supportedCalculator.supports(delayUntilRequest)).thenReturn(true);
      when(supportedCalculator.calculateDate(delayUntilRequest)).thenReturn(GIVEN_DATE);

      LocalDateTime dateValue = delayUntilResolver.resolve(delayUntilRequest);

      assertThat(dateValue).isEqualTo(GIVEN_DATE);
      verify(unsupportedCalculator).supports(delayUntilRequest);
      verify(unsupportedCalculator, never()).calculateDate(delayUntilRequest);
      verify(supportedCalculator).supports(delayUntilRequest);
      verify(supportedCalculator).calculateDate(delayUntilRequest);
    }
  }
}
