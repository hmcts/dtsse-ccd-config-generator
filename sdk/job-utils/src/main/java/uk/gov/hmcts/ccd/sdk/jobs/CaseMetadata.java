package uk.gov.hmcts.ccd.sdk.jobs;

/**
 * The case a {@link CaseJob} is applied to, as it stands with the case locked.
 *
 * @param reference the case reference
 * @param caseTypeId the case type
 * @param state the case's state
 */
public record CaseMetadata(long reference, String caseTypeId, String state) {
}
