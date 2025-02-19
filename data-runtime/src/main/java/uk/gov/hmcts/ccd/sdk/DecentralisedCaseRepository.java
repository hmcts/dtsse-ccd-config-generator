package uk.gov.hmcts.ccd.sdk;


public abstract class DecentralisedCaseRepository<CaseType> implements CaseRepository<CaseType> {
  public final CaseType getCase(long caseRef, CaseType data, String roleAssignments) {
    return getCase(caseRef, roleAssignments);
  }

  public abstract CaseType getCase(long caseRef, String roleAssignments);
}

