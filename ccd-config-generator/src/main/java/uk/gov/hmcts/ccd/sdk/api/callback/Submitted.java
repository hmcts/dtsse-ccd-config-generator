package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@FunctionalInterface
public interface Submitted<T, S> {
  SubmittedCallbackResponse handle(CaseDetails<T, S> details, CaseDetails<T, S> detailsBefore);
}
