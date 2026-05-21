package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

interface LegacyCallback {

  Optional<LegacyAboutToSubmitCallbackResponse> aboutToSubmit(CallbackRequest request);

  Optional<SubmittedCallbackResponse> submitted(CallbackRequest request);

  int submittedAttempts();
}
