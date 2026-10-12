package uk.gov.hmcts.ccd.sdk.bundling.managed;

/** Managed case bundles. These Java methods do not authorise callers or expose HTTP endpoints. */
public interface CaseBundles {
  /** Must join the transaction changing the source documents; rollback cancels the invalidation. */
  void invalidate(String definition, String caseKey);

  /**
   * Requests fresh selection and rendering even when the case's document references are unchanged.
   */
  void refresh(String definition, String caseKey);

  /** A consistent read of the current publication and whether it needs an update. */
  CaseBundleView view(String definition, String caseKey);
}
