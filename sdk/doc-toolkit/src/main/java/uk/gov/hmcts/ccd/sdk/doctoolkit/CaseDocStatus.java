package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;

/**
 * Where a kept-up-to-date document is, for showing "preparing", "updating" or "failed" beside
 * what the service has stored.
 *
 * @param state {@code QUEUED} or {@code IN_PROGRESS} while a newer version is on its way,
 *     {@code DONE} when the latest request has been handed over, {@code FAILED} when it was
 *     given up on
 * @param revision the case revision of the latest request
 * @param attempts the attempts at the latest request
 * @param failure why it failed, when it did
 */
public record CaseDocStatus(DocJobState state, long revision, int attempts,
    Optional<DocJob.Failure> failure) {
}
