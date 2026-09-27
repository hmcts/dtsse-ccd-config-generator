package uk.gov.hmcts.ccd.sdk.api.external;

/**
 * What an external event's submit handler is given: the case, the payload the frontend sent, and
 * who sent it.
 */
public record ExternalSubmitRequest<I>(long caseReference, I payload, ExternalUser user) {
}
