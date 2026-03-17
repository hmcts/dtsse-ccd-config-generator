package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

public interface CallbackHandler<T> {

  default String getHandledCaseTypeId() {
    return null;
  }

  String getHandledEventId();

  default CallbackResponse<T> aboutToSubmit(CallbackRequest data) {
    return null;
  }

  default boolean acceptsAboutToSubmit() {
    return false;
  }

  default SubmittedCallbackResponse submitted(CallbackRequest data) {
    return null;
  }

  default boolean acceptsSubmitted() {
    return false;
  }

  default int getSubmittedRetries() {
    return 0;
  }
}
