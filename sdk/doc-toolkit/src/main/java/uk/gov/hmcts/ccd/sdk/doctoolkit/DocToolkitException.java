package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.List;

/**
 * Rendering or uploading failed. {@link #isTransient()} says whether a retry may succeed.
 */
public class DocToolkitException extends RuntimeException {

  /** What failed. */
  public enum Reason {
    /** The request cannot be rendered as given. */
    INVALID_REQUEST,
    /**
     * Documents could not be included; {@link #failures()} lists them.
     */
    DOCUMENTS_FAILED,
    /** A document needs a service the toolkit was not given: Docmosis or CDAM. */
    NOT_CONFIGURED,
    /**
     * The print-ready output is larger than {@link PrintOptions#maxOutputBytes()}.
     */
    OUTPUT_TOO_LARGE,
    /** The documents could not be put together. */
    ASSEMBLY_FAILED,
    /** CDAM did not store or attach the result. */
    UPLOAD_FAILED,
    /** Local disk trouble. */
    IO_FAILURE
  }

  private final Reason reason;
  private final boolean transientFailure;
  private final transient List<DocFailure> failures;

  /** A failure with no document failures. */
  public DocToolkitException(Reason reason, String message, boolean transientFailure,
      Throwable cause) {
    this(reason, message, transientFailure, List.of(), cause);
  }

  /** A failure listing the documents that caused it. */
  public DocToolkitException(Reason reason, String message, boolean transientFailure,
      List<DocFailure> failures, Throwable cause) {
    super(message, cause);
    this.reason = Validate.requireNonNull(reason, "reason");
    this.transientFailure = transientFailure;
    this.failures = List.copyOf(failures);
  }

  /** What failed. */
  public Reason reason() {
    return reason;
  }

  /** Whether the same request may succeed later, such as after a CDAM or Docmosis outage. */
  public boolean isTransient() {
    return transientFailure;
  }

  /**
   * The documents that could not be included, for {@link Reason#DOCUMENTS_FAILED}.
   */
  public List<DocFailure> failures() {
    return failures;
  }
}
