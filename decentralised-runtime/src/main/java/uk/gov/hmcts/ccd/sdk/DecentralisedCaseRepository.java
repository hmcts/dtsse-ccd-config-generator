package uk.gov.hmcts.ccd.sdk;


public abstract class DecentralisedCaseRepository<CaseType> implements CaseRepository<CaseType> {
  public final CaseType getCase(long caseRef, String state, CaseType data) {
    return getCase(caseRef, state);
  }

  public abstract CaseType getCase(long caseRef, String state);
}

