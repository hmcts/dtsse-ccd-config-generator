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
  /** A source document could not be resolved for another reason. */
  DOCUMENT_RESOLUTION_FAILED,
  /** A document's media type has no registered handler. */
  MEDIA_TYPE_UNSUPPORTED,
  /**
   * A source document's content is irreconcilable with its declared media type — for example
   * declared PDF but detected as a non-office ZIP archive. The error names both types.
   */
  DOCUMENT_CONTENT_INVALID,
  /**
   * A bundle contains an office-format document but the Docmosis render service is not
   * configured and no replacement handler is registered.
   */
  DOCMOSIS_NOT_CONFIGURED,
  /** A handler failed to produce the PDF representation of a source document. */
  DOCUMENT_CONVERSION_FAILED,
  /** A converted PDF failed readability inspection: encrypted, empty, corrupt or malformed. */
  DOCUMENT_INSPECTION_FAILED,
  /** The Docmosis cover-page template could not be rendered. */
  COVER_PAGE_FAILED,
  /** PDF assembly of the bundle failed. */
  ASSEMBLY_FAILED,
  /** A configured maximum (documents, bytes, or pages) was breached. */
  LIMIT_EXCEEDED,
  /** A durable job's persisted request could not be read by the executing worker. */
  JOB_REQUEST_UNREADABLE,
  /** The bundle rendered but the consumer's completion handler failed to store it. */
  COMPLETION_FAILED
}
