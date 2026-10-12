package uk.gov.hmcts.ccd.sdk.bundling.job;

/** A durable publication resumed without rendering its PDF again. */
public record CompletedBundleJob(BundleJobReport report, Object summary) {}
