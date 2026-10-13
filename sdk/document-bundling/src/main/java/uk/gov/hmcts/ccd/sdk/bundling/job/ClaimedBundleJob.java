package uk.gov.hmcts.ccd.sdk.bundling.job;

/** One outbox row claimed by a worker, with the persisted JSON payloads it executes from. */
record ClaimedBundleJob(
    BundleJob job,
    int requestVersion,
    String requestJson,
    String selectorParametersJson,
    String executionContextJson,
    String transientHistoryJson) {
}
