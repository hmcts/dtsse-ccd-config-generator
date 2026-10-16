package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Whether the caller will retry a render that fails because a source was temporarily
 * unavailable. Only matters under {@link MissingDocumentPolicy#PLACEHOLDER}.
 */
public enum RenderAttempt {

  /** The caller retries: a temporarily unavailable source fails the render. */
  RETRYABLE,

  /** No retry follows: a temporarily unavailable source becomes a placeholder page. */
  FINAL
}
