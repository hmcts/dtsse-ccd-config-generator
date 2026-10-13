package uk.gov.hmcts.ccd.sdk.bundling.api;

/** A typed resolution failure for one document reference. */
public record ResolutionFailure(ResolutionFailureReason reason, String detail) {

  public ResolutionFailure {
    Validate.requireNonNull(reason, "ResolutionFailure.reason");
    Validate.requireNonNull(detail, "ResolutionFailure.detail");
  }
}
