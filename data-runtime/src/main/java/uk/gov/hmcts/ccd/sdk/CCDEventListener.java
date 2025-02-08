package uk.gov.hmcts.ccd.sdk;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.AboutToStartOrSubmitCallbackResponse;


public interface CCDEventListener {
  AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request);
  boolean hasSubmittedCallbackForEvent(String event);
  boolean hasAboutToSubmitCallbackForEvent(String event);
}
