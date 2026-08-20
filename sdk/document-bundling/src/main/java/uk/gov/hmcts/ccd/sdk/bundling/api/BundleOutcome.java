package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Successful bundle outcomes. Failures are never an outcome: they throw a typed
 * {@link BundleGenerationException} synchronously or end a durable job as failed.
 */
public enum BundleOutcome {

  /** Every document stitched with no warnings. */
  COMPLETED,

  /**
   * Every document stitched, with non-fatal presentational warnings — for example an included
   * empty-section page. A warning-carrying result must not be presented as a plain success in
   * the consumer UI or audit trail.
   */
  COMPLETED_WITH_WARNINGS
}
