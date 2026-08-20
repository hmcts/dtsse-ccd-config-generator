package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.UUID;

/**
 * One outbox row claimed by a worker: everything the worker needs to execute the job without
 * re-reading the row.
 *
 * @param externalId the job's idempotency key
 * @param attempts the attempt count after this claim (a claim increments it)
 * @param requestVersion the version of the persisted request JSON
 * @param requestJson the submitted request JSON; null for a selector-parameters submission
 * @param selectorParametersJson the selector parameters JSON
 * @param executionContextJson the non-secret execution context JSON
 * @param transientHistoryJson the transient failures recorded by earlier attempts, as JSON
 */
record ClaimedBundleJob(
    UUID externalId,
    int attempts,
    int requestVersion,
    String requestJson,
    String selectorParametersJson,
    String executionContextJson,
    String transientHistoryJson) {
}
