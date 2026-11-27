package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Typed reasons a document reference could not be resolved. Raw downstream messages and
 * credentials must not be placed in user-visible results.
 */
public enum ResolutionFailureReason {

  /** The document does not exist at the provider. */
  NOT_FOUND,

  /**
   * The execution context is not permitted to read the document. Fatal, and distinguishable from
   * {@link #NOT_FOUND} only in restricted operational diagnostics.
   */
  ACCESS_DENIED,

  /**
   * The provider rejected the service's own credentials, for example an expired token. Retried
   * while the job has attempts left, and never replaced by a placeholder.
   */
  CREDENTIALS_REJECTED,

  /** A transient infrastructure failure. */
  TRANSIENT_FAILURE,

  /** The document's media type is not supported by the resolver. */
  UNSUPPORTED_MEDIA_TYPE,

  /** The content is corrupt or does not match its declared type. */
  INVALID_CONTENT,

  /** The content exceeds the configured size limits. */
  TOO_LARGE
}
