package uk.gov.hmcts.ccd.sdk.runtime;

import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

@FunctionalInterface
public interface RuntimeCallback<R> {

  R invoke(CallbackRequest request, Context context);

  interface Context {

    CaseDetails<?, ?> convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails);

    CaseDetails<?, ?> convertCaseDetails(
        uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails,
        String caseType
    );

    EventPayload<?, ?> startPayload(CallbackRequest request);
  }
}
