package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import java.time.LocalDateTime;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

class DelayUntilDateTimeCalculator implements DelayUntilCalculator {

  @Override
  public boolean supports(DelayUntilRequest delayUntilRequest) {
    return Optional.ofNullable(delayUntilRequest.getDelayUntilTime()).isPresent()
        && Optional.ofNullable(delayUntilRequest.getDelayUntilOrigin()).isEmpty()
        && Optional.ofNullable(delayUntilRequest.getDelayUntil()).isEmpty();
  }

  @Override
  public LocalDateTime calculateDate(DelayUntilRequest delayUntilRequest) {
    return addTimeToDate(delayUntilRequest.getDelayUntilTime(), LocalDateTime.now());
  }
}
