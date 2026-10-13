package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.UUID;

/** A progress event for one durable job: the state it is now in and the document counts. */
public record BundleProgressEvent(
    UUID externalId,
    BundleJobState state,
    int completedDocuments,
    int totalDocuments) {

  public BundleProgressEvent {
    if (externalId == null || state == null) {
      throw new IllegalArgumentException("BundleProgressEvent.externalId and state must be provided");
    }
    if (completedDocuments < 0 || completedDocuments > totalDocuments) {
      throw new IllegalArgumentException(
          "BundleProgressEvent document counts must satisfy 0 <= completed <= total");
    }
  }
}
