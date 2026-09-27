package uk.gov.hmcts.ccd.sdk.api.external;

/**
 * What an external event's start handler is given when a frontend starts the event: the case it
 * is starting on and who is starting it. The handler loads whatever it needs to send the frontend.
 */
public record ExternalStartRequest(long caseReference, ExternalUser user) {
}
