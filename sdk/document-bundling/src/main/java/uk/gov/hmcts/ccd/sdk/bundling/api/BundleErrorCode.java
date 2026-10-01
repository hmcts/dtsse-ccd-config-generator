package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * The documented error catalogue. Codes are stable, enumerated here, and safe to alert on; new
 * failure modes get new codes rather than being folded into generic ones.
 */
public enum BundleErrorCode {
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
  /** A source document's content is corrupt or does not match its declared media type. */
  DOCUMENT_CONTENT_INVALID,
  /**
   * A bundle contains an office-format document but the Docmosis render service is not
   * configured and no replacement handler is registered.
   */
  DOCMOSIS_NOT_CONFIGURED,
  /** A handler failed to produce the PDF representation of a source document. */
  DOCUMENT_CONVERSION_FAILED,
  /** PDF assembly of the bundle failed. */
  ASSEMBLY_FAILED,
  /** A configured maximum (documents, bytes, or pages) was breached. */
  LIMIT_EXCEEDED
}
