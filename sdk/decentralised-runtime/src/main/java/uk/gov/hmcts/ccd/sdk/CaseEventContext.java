package uk.gov.hmcts.ccd.sdk;

/**
 * The event a {@link CaseEventHook} is running in.
 *
 * @param caseReference the case's 16-digit reference
 * @param caseType the case type id
 * @param jurisdiction the jurisdiction id
 * @param eventId the CCD event id, for example {@code createBundle}
 * @param caseEventId the id of the event's audit record
 * @param revision the case's revision after the event; it increases with every event
 */
public record CaseEventContext(long caseReference, String caseType, String jurisdiction,
    String eventId, long caseEventId, long revision) {
}
