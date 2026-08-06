package uk.gov.hmcts.ccd.sdk;

public record SystemCaseEventResult(long caseReference, long eventInstanceId, boolean replayed) {
}
