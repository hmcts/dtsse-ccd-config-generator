package uk.gov.hmcts.ccd.sdk.api.callback;

import uk.gov.hmcts.ccd.sdk.api.EventPayload;

@FunctionalInterface
public interface Submit<CaseType, State> {
  SubmitResponse<State> submit(EventPayload<CaseType, State> payload);

}
