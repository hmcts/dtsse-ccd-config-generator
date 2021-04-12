package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.CaseDetails;

@FunctionalInterface
public interface AboutToStart<T, S> {
  AboutToStartOrSubmitResponse<T, S> handle(CaseDetails<T, S> details);
}
