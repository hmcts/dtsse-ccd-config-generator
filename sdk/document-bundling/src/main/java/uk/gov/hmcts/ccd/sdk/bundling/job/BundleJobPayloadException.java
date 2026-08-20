package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * A persisted job payload — the stored request, selector parameters, or execution context —
 * could not be read by the current worker version. The worker fails the job clearly with
 * {@link uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode#JOB_REQUEST_UNREADABLE} rather than
 * guessing.
 */
class BundleJobPayloadException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message what could not be read
   */
  BundleJobPayloadException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what could not be read
   * @param cause the underlying parse failure
   */
  BundleJobPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
