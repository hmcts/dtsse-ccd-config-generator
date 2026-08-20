package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * What to render when an expected section contains no documents at generation time.
 */
public enum EmptySectionPolicy {

  /** Omit the section entirely from the bundle, its contents and its bookmarks. */
  OMIT,

  /** Render the standard visible empty-section page in the section's position. */
  INCLUDE_PLACEHOLDER
}
