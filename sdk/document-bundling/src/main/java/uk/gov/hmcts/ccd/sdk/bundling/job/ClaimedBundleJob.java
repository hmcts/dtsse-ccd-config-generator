package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * One outbox row claimed by a worker. The lease owner is unique to this claim, so a reclaimed
 * job's earlier attempt cannot write over it.
 */
record ClaimedBundleJob(
    BundleJob job,
    int unavailableRequeues,
    int priority,
    String transientHistoryJson,
    String leaseOwner) {
}
