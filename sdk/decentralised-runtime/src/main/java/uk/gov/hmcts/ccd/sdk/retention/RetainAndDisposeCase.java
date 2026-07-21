package uk.gov.hmcts.ccd.sdk.retention;

record RetainAndDisposeCase(
    long reference,
    String jurisdiction,
    String caseTypeId,
    String state
) {
}
