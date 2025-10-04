package uk.gov.hmcts.ccd.sdk;

import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

public interface CCDEventListener {
  AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request);

  SubmitResponse submit(String caseType, String event, DecentralisedCaseEvent payload,
                        MultiValueMap<String, String> urlParams);

  String nameForState(String caseType, String stateId);

  SubmittedCallbackResponse submitted(CallbackRequest request);
}
