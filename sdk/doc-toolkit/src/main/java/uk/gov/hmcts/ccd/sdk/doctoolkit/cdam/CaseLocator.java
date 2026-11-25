package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

/** Finds the case type and jurisdiction CDAM needs to attach a document to a case. */
@FunctionalInterface
public interface CaseLocator {

  /** The case's type and jurisdiction. */
  CaseType locate(long caseId);

  /** A case's type and jurisdiction. */
  record CaseType(String caseTypeId, String jurisdiction) {
  }
}
