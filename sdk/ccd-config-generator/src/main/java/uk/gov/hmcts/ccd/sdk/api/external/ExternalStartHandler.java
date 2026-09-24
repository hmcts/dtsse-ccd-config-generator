package uk.gov.hmcts.ccd.sdk.api.external;

/** Builds the payload an external event sends its frontend when it starts the event. */
@FunctionalInterface
public interface ExternalStartHandler<O> {
  ExternalStartResponse<O> start(ExternalStart start);
}
