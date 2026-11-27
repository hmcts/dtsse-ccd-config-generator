package uk.gov.hmcts.ccd.sdk.jobs;

import java.util.UUID;

/**
 * A case as a {@link CaseJob} is applied to it: its row, read in the job's transaction (after the
 * case is locked, if the job locks it).
 *
 * @param reference the case reference
 * @param caseTypeId the case type
 * @param state the case's state
 * @param revision the case's revision, which an event that changes the case increases
 * @param idempotencyKey the same each time this run applies the job to this case, for a system
 *     event the job runs, so the event runs once
 */
public record CaseMetadata(long reference, String caseTypeId, String state, long revision,
    UUID idempotencyKey) {
}
