package uk.gov.hmcts.ccd.sdk.bundling.managed;

/** Managed case bundles. These Java methods do not authorise callers or expose HTTP endpoints. */
public interface CaseBundles {
  /**
   * The documents this bundle is built from may have changed, so the bundle is selected and rendered
   * again after commit. Must join the transaction that changed them; rollback cancels it.
   */
  void changed(String definition, String caseKey);

  /** A consistent read of the current publication and whether it needs an update. */
  CaseBundleView view(String definition, String caseKey);
}
