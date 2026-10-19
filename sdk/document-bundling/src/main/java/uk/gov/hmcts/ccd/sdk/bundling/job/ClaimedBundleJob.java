package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * One outbox row claimed by a worker, with the persisted JSON payloads it executes from. The lease
 * owner is unique to this claim, so a reclaimed job's earlier attempt cannot write over it.
 */
record ClaimedBundleJob(
    BundleJob job,
    int requestVersion,
    String requestJson,
    String selectorParametersJson,
    String executionContextJson,
    String transientHistoryJson,
    String leaseOwner) {
}
