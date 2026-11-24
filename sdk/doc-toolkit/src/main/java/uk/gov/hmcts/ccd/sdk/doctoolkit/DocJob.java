package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A queued render.
 *
 * @param caseId the case the document is stored against
 * @param eventId the event that queued it, which a {@link DocJobHandler} can use to tell its jobs
 *     apart
 * @param attempts how many times a worker has run it
 * @param result the stored document, once rendered and uploaded
 * @param failure why it was given up on
 */
public record DocJob(UUID id, DocJobState state, long caseId, String eventId, int attempts,
    Instant submittedAt, Instant updatedAt, Optional<UploadedDoc> result,
    Optional<Failure> failure) {

  /**
   * Why a job was given up on.
   *
   * @param reason a {@link DocToolkitException.Reason} name, {@code HANDLER_FAILED} when the
   *     handler kept failing, {@code CRASHED} when every attempt's worker died, or
   *     {@code UNREADABLE_REQUEST}
   * @param documents the documents that could not be included, if that was the reason
   */
  public record Failure(String reason, String message, List<DocFailure> documents) {
    public Failure {
      documents = List.copyOf(documents);
    }
  }
}
