package uk.gov.hmcts.ccd.sdk;


public abstract class DecentralisedCaseRepository<CaseType> implements CaseRepository<CaseType> {
  public final CaseType getCase(long caseRef, CaseType data) {
    return getCase(caseRef);
  }

  public abstract CaseType getCase(long caseRef);
}

