package uk.gov.hmcts.ccd.sdk;

public interface CaseRepository<CaseType> {
    CaseType getCase(long caseRef, CaseType data, String roleAssignments);
}
