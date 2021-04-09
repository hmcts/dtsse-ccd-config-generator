package uk.gov.hmcts.ccd.sdk.api;

@FunctionalInterface
public interface CallbackHandler<T, S> {
  AboutToStartOrSubmitCallbackResponse<T, S> handle(CaseDetails<T, S> details);
}
