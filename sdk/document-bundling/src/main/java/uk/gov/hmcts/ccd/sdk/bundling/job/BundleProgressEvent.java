package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.UUID;

/**
 * A progress event for one durable job. A percentage may be derived for a UI, but the SDK does
 * not claim precise completion when conversion and final PDF writing have unknown cost.
 *
 * @param externalId the job's idempotency key
 * @param state the state the job is now in
 * @param completedDocuments documents completed in the current stage
 * @param totalDocuments total documents in the request
 */
public record BundleProgressEvent(
    UUID externalId,
    BundleJobState state,
    int completedDocuments,
    int totalDocuments) {

  public BundleProgressEvent {
    if (externalId == null || state == null) {
      throw new IllegalArgumentException(
          "BundleProgressEvent.externalId and state must be provided");
    }
    if (completedDocuments < 0 || totalDocuments < 0 || completedDocuments > totalDocuments) {
      throw new IllegalArgumentException(
          "BundleProgressEvent document counts must satisfy 0 <= completed <= total");
    }
  }
}
