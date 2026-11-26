package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A render of a case's kept-up-to-date document, as the service's {@link DocJobHandler} sees it.
 *
 * @param caseReference the case the document belongs to
 * @param key the {@link CaseDocDefinition#key()}
 * @param revision the case revision the request was built at
 * @param attempts how many times a worker has claimed it
 * @param failure why it was given up on
 */
public record DocJob(UUID id, long caseReference, String key, long revision, DocJobState state,
    int attempts, Instant submittedAt, Instant updatedAt, Optional<Failure> failure) {

  /**
   * Why a job was given up on.
   *
   * @param reason a {@link DocToolkitException.Reason} name, {@code HANDLER_FAILED} when the
   *     handler kept failing, {@code CRASHED} when every attempt's worker died, or
   *     {@code UNREADABLE_REQUEST}
   * @param documents the documents that could not be included, when that was the reason
   */
  public record Failure(String reason, String message, List<DocFailure> documents) {
    public Failure {
      documents = List.copyOf(documents);
    }
  }
}
