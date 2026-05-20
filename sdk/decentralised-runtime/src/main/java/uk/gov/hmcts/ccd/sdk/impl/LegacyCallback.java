package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

interface LegacyCallback {

  Optional<LegacyAboutToSubmitCallbackResponse> aboutToSubmit(CallbackRequest request, String authorisation);

  Optional<LegacySubmittedCallbackResponse> submitted(CallbackRequest request, String authorisation);

  int submittedAttempts();
}
