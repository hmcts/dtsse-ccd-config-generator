package uk.gov.hmcts.ccd.sdk.api.external;

/** Handles the payload a frontend submits to an external event. */
@FunctionalInterface
public interface ExternalSubmitHandler<S, I> {
  ExternalSubmitResponse<S> submit(ExternalSubmitRequest<I> submit);
}
