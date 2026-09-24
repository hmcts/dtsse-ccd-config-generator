package uk.gov.hmcts.ccd.sdk.api.external;

/** What an external event's submit handler is given: the case and the payload the frontend sent. */
public record ExternalSubmit<I>(long caseReference, I payload) {
}
