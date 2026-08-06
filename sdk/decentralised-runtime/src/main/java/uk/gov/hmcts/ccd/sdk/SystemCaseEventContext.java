package uk.gov.hmcts.ccd.sdk;

public record SystemCaseEventContext<T, S>(long caseReference, T caseData, S state) {
}
