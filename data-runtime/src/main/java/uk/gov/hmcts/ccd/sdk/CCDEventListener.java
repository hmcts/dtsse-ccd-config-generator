package uk.gov.hmcts.ccd.sdk;

import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.AboutToStartOrSubmitCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;


public interface CCDEventListener {
  AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request);

  boolean hasSubmittedCallbackForEvent(String caseType, String event);

  boolean hasAboutToSubmitCallbackForEvent(String caseType, String event);

  boolean hasSubmitHandler(String caseType, String event);

  void submit(String caseType, String event, POCCaseEvent payload, MultiValueMap<String, String> urlParams);

  String nameForState(String caseType, String stateId);

  SubmittedCallbackResponse submitted(CallbackRequest request);
}
