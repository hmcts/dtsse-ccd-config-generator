package uk.gov.hmcts.ccd.sdk.impl;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
class CallbackValidationException extends RuntimeException {
  private final List<String> errors;
  private final List<String> warnings;

}
