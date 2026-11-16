package uk.gov.hmcts.ccd.sdk.bundling.managed;

/** Application-owned case selection and document selection. */
public interface CaseBundleDefinition {
  String name();

  /** Select the current documents inside a short, repeatable-read transaction. */
  SelectedCaseBundle select(String caseKey);

  /**
   * Override to support bulk refresh: trusted, constant SQL returning a text column {@code case_key}
   * for every case to refresh, using the same keys as select. Empty opts out. Never use external input.
   */
  default String caseKeysSql() {
    return "";
  }
}
