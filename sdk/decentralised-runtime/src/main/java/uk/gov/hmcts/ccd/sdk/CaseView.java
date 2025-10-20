package uk.gov.hmcts.ccd.sdk;

public interface CaseView<CaseType> {
  CaseType getCase(long caseRef, String state, CaseType blobCase);
}
