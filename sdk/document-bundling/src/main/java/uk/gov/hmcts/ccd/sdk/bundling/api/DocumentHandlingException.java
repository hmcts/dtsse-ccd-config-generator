package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Thrown by a {@link DocumentHandler} when a source document cannot be represented as PDF. The
 * pipeline attributes it to the document and fails the bundle with a typed error.
 */
public class DocumentHandlingException extends Exception {

  /** Creates the exception. */
  public DocumentHandlingException(String message) {
    super(message);
  }

  /** Creates the exception with a cause. */
  public DocumentHandlingException(String message, Throwable cause) {
    super(message, cause);
  }
}
