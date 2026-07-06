package uk.gov.hmcts.ccd.sdk.retention;

public record RetentionTaskResult(
    int deletedCases,
    int simulatedCases,
    int skippedCases
) {
}
