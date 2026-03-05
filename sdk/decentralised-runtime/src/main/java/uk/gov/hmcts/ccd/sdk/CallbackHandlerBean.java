package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

public interface CallbackHandlerBean {

  String getHandledEventId();

  default CallbackResponse aboutToSubmit(CallbackRequest callbackRequest) {
    return null;
  }

  default SubmittedCallbackResponse submitted(CallbackRequest callbackRequest) {
    return null;
  }

}
