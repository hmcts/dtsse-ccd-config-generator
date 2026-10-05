package uk.gov.hmcts.ccd.sdk.impl;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;

@Getter
@RequiredArgsConstructor
class CallbackValidationException extends RuntimeException {
  private final List<String> errors;
  private final List<String> warnings;

  /**
   * Rejects the submission as CCD would: on any error, or on warnings the user has not chosen to ignore.
   * Rejecting here, inside the event's transaction, stops the event's writes committing before CCD rejects it.
   */
  static void throwIfRejected(List<String> errors, List<String> warnings, boolean ignoreWarning) {
    if (!CollectionUtils.isEmpty(errors) || (!CollectionUtils.isEmpty(warnings) && !ignoreWarning)) {
      throw new CallbackValidationException(errors, warnings);
    }
  }
}
