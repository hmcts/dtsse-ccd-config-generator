package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * A job as the service's {@link DocJobHandler} sees it.
 *
 * @param caseReference the case the document belongs to
 * @param key the {@link CaseDocDefinition#key()} of a kept-up-to-date document; empty for a
 *     one-off job
 * @param revision the case revision the request was built at; empty for a one-off job. Store
 *     results only if newer than what is held.
 * @param attempts how many times a worker has claimed it
 * @param result the stored document, once rendered and uploaded
 * @param failure why it was given up on
 */
public record DocJob(UUID id, long caseReference, Optional<String> key, OptionalLong revision,
    DocJobState state, int attempts, Instant submittedAt, Instant updatedAt,
    Optional<UploadedDoc> result, Optional<Failure> failure) {

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

  DocJob withResult(UploadedDoc uploaded) {
    return new DocJob(id, caseReference, key, revision, state, attempts, submittedAt, updatedAt,
        Optional.of(uploaded), failure);
  }
}
