package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * The documented error catalogue. Codes are stable, enumerated here, and safe to alert on; new
 * failure modes get new codes rather than being folded into generic ones.
 */
public enum BundleErrorCode {

  /** The bundle request failed validation before any content was read. */
  REQUEST_INVALID,

  /** A source document does not exist at its provider. */
  DOCUMENT_NOT_FOUND,

  /**
   * Access to a source document was denied. Fatal; distinguishable from
   * {@link #DOCUMENT_NOT_FOUND} only in restricted operational diagnostics.
   */
  DOCUMENT_ACCESS_DENIED,

  /** A source document could not be resolved for another reason, including exhausted retries. */
  DOCUMENT_RESOLUTION_FAILED,

  /** A document's media type has no registered handler. */
  MEDIA_TYPE_UNSUPPORTED,

  /**
   * A source document's content is irreconcilable with its declared media type — for example
   * declared PDF but detected as a non-office ZIP archive — or a resolver reported the content
   * as corrupt. The error names both the declared and detected types.
   */
  DOCUMENT_CONTENT_INVALID,

  /**
   * A bundle contains an office-format document but the Docmosis render service is not
   * configured and no replacement handler is registered.
   */
  DOCMOSIS_NOT_CONFIGURED,

  /** A handler failed to produce the PDF representation of a source document. */
  DOCUMENT_CONVERSION_FAILED,

  /** A converted PDF failed evidence-readability inspection, for example encrypted or corrupt. */
  DOCUMENT_INSPECTION_FAILED,

  /** PDF assembly of the bundle failed. */
  ASSEMBLY_FAILED,

  /** The finished artifact failed final validation; nothing was published. */
  OUTPUT_VALIDATION_FAILED,

  /**
   * The destination failed to store the artifact for a transient reason — an I/O failure or a
   * downstream 5xx; nothing was published. Eligible for the durable job runner's bounded retry.
   */
  STORAGE_FAILED,

  /**
   * The destination rejected the artifact or its case attachment permanently — for example a
   * CDAM 4xx from the upload or from {@code attachToCase} (S2S identity not onboarded with
   * {@code ATTACH}, invalid upload coordinates or case reference); nothing was published. Never
   * retried: with unchanged configuration and request a retry cannot succeed, and each attempt
   * would create a fresh orphaned upload.
   */
  STORAGE_REJECTED,

  /** A configured maximum (documents, bytes, or pages) was breached. */
  LIMIT_EXCEEDED,

  /** The hard end-to-end timeout elapsed; the error carries per-stage timings. */
  TIMED_OUT,

  /** A durable job's persisted request could not be read by the current worker version. */
  JOB_REQUEST_UNREADABLE
}
