package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

/**
 * A bounded, sanitised Docmosis failure. The message must be log-safe: no access key and no raw
 * response body. The pipeline maps it to a typed conversion failure identifying the document.
 */
public class DocmosisRenderException extends Exception {

  private final boolean transientFailure;

  /** Creates the exception. */
  public DocmosisRenderException(String message, boolean transientFailure) {
    super(message);
    this.transientFailure = transientFailure;
  }

  /** Creates the exception with a cause. */
  public DocmosisRenderException(String message, boolean transientFailure, Throwable cause) {
    super(message, cause);
    this.transientFailure = transientFailure;
  }

  /** Whether the failure is transient and safe to retry within bounds. */
  public boolean isTransientFailure() {
    return transientFailure;
  }
}
