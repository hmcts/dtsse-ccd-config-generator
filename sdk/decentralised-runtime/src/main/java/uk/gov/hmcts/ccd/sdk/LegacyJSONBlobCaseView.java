package uk.gov.hmcts.ccd.sdk;

public abstract class LegacyJSONBlobCaseView<CaseType> implements CaseView<CaseType> {
    @Override
    public final CaseType getCase(long caseRef, String state, CaseType blobCase) {
        return this.projectCase(caseRef, state, blobCase);
    }

  protected abstract CaseType projectCase(long caseRef, String state, CaseType blobCase);
}
