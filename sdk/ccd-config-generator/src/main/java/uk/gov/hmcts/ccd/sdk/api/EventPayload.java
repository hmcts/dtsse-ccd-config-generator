package uk.gov.hmcts.ccd.sdk.api;

// TODO: caseReference is nullable currently.
// We should have a separate start event for create case start events that do not have a case reference.
public record EventPayload<T, S>(Long caseReference, T caseData) {
}
