package uk.gov.hmcts.ccd.sdk.taskmanagement.delay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator;

/**
 * Resolves a {@link LocalDateTime} from a {@link DelayUntilRequest}.
 *
 * <p>Consumers normally obtain this class as the Spring bean contributed by
 * {@link DelayUntilAutoConfiguration} and pass in a request describing one of the supported delay
 * styles:
 *
 * <ul>
 *   <li>{@code delayUntil}: use an explicit date or date-time value.
 *   <li>{@code delayUntilTime}: use a time on the current day when no date or origin is supplied.
 *   <li>{@code delayUntilOrigin} with optional interval and working-day settings: calculate a date
 *       relative to the origin.
 * </ul>
 *
 * <p>The request is matched against the first registered calculator that supports it. If no
 * calculator supports the supplied shape, the resolver falls back to {@link LocalDateTime#now()}.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DelayUntilResolver {

  private final List<DelayUntilCalculator> delayUntilCalculators;

  /**
   * Resolves the requested delay-until value.
   *
   * <p>Built-in behaviour is:
   *
   * <ul>
   *   <li>When {@code delayUntil} is supplied, that explicit value is used. If it contains only a
   *       date, the current time is used unless {@code delayUntilTime} is also supplied.
   *   <li>When only {@code delayUntilTime} is supplied, today's date is used.
   *   <li>When {@code delayUntilOrigin} is supplied, the result is calculated relative to that
   *       origin using {@code delayUntilIntervalDays} and the optional working-day fields.
   * </ul>
   *
   * <p>If the request does not match any registered calculator, this method returns
   * {@link LocalDateTime#now()}.
   *
   * @param delayUntilRequest request describing how to calculate the target date-time
   * @return resolved delay-until date-time, or {@link LocalDateTime#now()} when no calculator
   *     supports the request
   */
  public LocalDateTime resolve(DelayUntilRequest delayUntilRequest) {
    logInput(delayUntilRequest);
    return delayUntilCalculators.stream()
        .filter(delayUntilCalculator -> delayUntilCalculator.supports(delayUntilRequest))
        .findFirst()
        .map(dateCalculator -> dateCalculator.calculateDate(delayUntilRequest))
        .orElse(LocalDateTime.now());
  }

  private static void logInput(DelayUntilRequest delayUntilRequest) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      log.info(
          "Delay until value for calculation is : {}",
          objectMapper.writeValueAsString(delayUntilRequest)
      );
    } catch (JsonProcessingException jpe) {
      log.error(jpe.getMessage());
    }
  }
}
