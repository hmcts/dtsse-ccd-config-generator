package uk.gov.hmcts.ccd.sdk.bundling.managed;

/** Application selection rules; called inside a short, repeatable-read transaction. */
public interface CaseBundleDefinition {
  String name();

  SelectedCaseBundle select(String caseKey);
}
