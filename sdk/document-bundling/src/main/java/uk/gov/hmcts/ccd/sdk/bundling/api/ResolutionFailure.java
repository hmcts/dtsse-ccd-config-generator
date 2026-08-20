package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * A typed resolution failure for one document reference.
 *
 * @param reason the typed failure reason
 * @param detail a log-safe description; never a raw downstream error body or credential
 */
public record ResolutionFailure(ResolutionFailureReason reason, String detail) {

  public ResolutionFailure {
    Validate.requireNonNull(reason, "ResolutionFailure.reason");
    Validate.requireNonNull(detail, "ResolutionFailure.detail");
  }
}
