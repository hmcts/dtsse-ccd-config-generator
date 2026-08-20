package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStorageException;

/**
 * The CDAM publication of a finished bundle failed: the upload, the attach, or a response the
 * destination could not publish from.
 *
 * <p>Carries {@link BundleStorageException}'s permanence classification: a permanent failure —
 * a CDAM 4xx from the upload or the attach, or a case reference that CDAM must reject — maps to
 * {@code STORAGE_REJECTED} and is never retried (each retry would orphan a fresh upload); other
 * failures are transient ({@code STORAGE_FAILED}) and eligible for bounded retry.
 *
 * <p>Messages are descriptive but sanitised: they name the bundle file and, where one exists, the
 * HTTP status — never tokens and never a raw downstream response body. Downstream client
 * exceptions whose messages embed the response body are deliberately not chained as the cause.
 */
public class CdamUploadException extends BundleStorageException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a transient (retryable) failure with a sanitised, descriptive message.
   *
   * @param message the log-safe description of the failure
   */
  public CdamUploadException(String message) {
    this(message, false);
  }

  /**
   * Creates the exception with a sanitised, descriptive message.
   *
   * @param message the log-safe description of the failure
   * @param permanent whether the failure is a permanent rejection, never to be retried
   */
  public CdamUploadException(String message, boolean permanent) {
    super(message, permanent);
  }

  /**
   * Creates a transient (retryable) failure with a sanitised, descriptive message and a log-safe
   * cause.
   *
   * @param message the log-safe description of the failure
   * @param cause the underlying failure; must not carry a raw downstream response body
   */
  public CdamUploadException(String message, Throwable cause) {
    super(message, false, cause);
  }
}
