package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * One queue row claimed by a worker. The lease owner is unique to this claim, so an earlier
 * render of the bundle whose lease was taken over cannot write over it.
 */
record ClaimedBundleJob(BundleJob job, String leaseOwner) {
}
