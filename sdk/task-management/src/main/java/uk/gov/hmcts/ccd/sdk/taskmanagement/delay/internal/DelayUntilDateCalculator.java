package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

class DelayUntilDateCalculator implements DelayUntilCalculator {

  @Override
  public boolean supports(DelayUntilRequest delayUntilRequest) {
    return Optional.ofNullable(delayUntilRequest.getDelayUntil()).isPresent();
  }

  @Override
  public LocalDateTime calculateDate(DelayUntilRequest delayUntilRequest) {
    var delayUntilResponse = delayUntilRequest.getDelayUntil();
    var delayUntilTimeResponse = delayUntilRequest.getDelayUntilTime();
    if (Optional.ofNullable(delayUntilTimeResponse).isPresent()) {
      return addTimeToDate(delayUntilTimeResponse, parseDateTime(delayUntilResponse));
    }
    LocalDateTime parseDateTime = parseDateTime(delayUntilResponse);
    if (parseDateTime.getHour() == 0 && parseDateTime.getMinute() == 0) {
      LocalTime localTime = LocalTime.now();
      return parseDateTime.withHour(localTime.getHour()).withMinute(localTime.getMinute());
    }
    return parseDateTime;
  }
}
