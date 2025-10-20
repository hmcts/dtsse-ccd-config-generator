package uk.gov.hmcts.ccd.sdk;


public abstract class DecentralisedCaseView<CaseType> implements CaseView<CaseType> {
  public final CaseType getCase(long caseRef, String state, CaseType data) {
    return getCase(caseRef, state);
  }

  public abstract CaseType getCase(long caseRef, String state);
}

