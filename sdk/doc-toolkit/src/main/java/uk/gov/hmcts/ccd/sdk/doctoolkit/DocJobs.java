package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Queues renders to run in the background. Spring Boot services get one when
 * {@code ccd.doc-toolkit.jobs.enabled} is true.
 *
 * <pre>{@code
 * docJobs.submit(BundleRequest.builder("Hearing bundle")...build(), caseId, eventId);
 * }</pre>
 *
 * <p>The job is written in the caller's transaction, so it is only queued if the event that
 * queued it commits. A worker renders it, stores the PDF in CDAM, attaches it to the case and
 * hands the stored document to the service's {@link DocJobHandler}. A render that fails for a
 * reason that may pass (CDAM, Docmosis or upload trouble) is retried, up to three attempts.
 */
public final class DocJobs {
  private final DocJobRepository repository;

  /** Created by the auto-configuration. */
  public DocJobs(DocJobRepository repository) {
    this.repository = Validate.requireNonNull(repository, "repository");
  }

  /**
   * Queues the request.
   *
   * @param caseId the case the document is stored against
   * @param eventId the event queuing it, passed back to the {@link DocJobHandler}
   * @throws IllegalArgumentException if a document is a local file, which the instance running
   *     the job may not have; queue its content with {@link DocSource#bytes} instead
   */
  public DocJob submit(DocRequest request, long caseId, String eventId) {
    Validate.requireNonNull(request, "request");
    Validate.requireNonBlank(eventId, "eventId");
    UUID id = UUID.randomUUID();
    repository.insert(id, DocRequestJson.write(request), caseId, eventId);
    return repository.find(id).orElseThrow();
  }

  /** A job, by id. */
  public Optional<DocJob> find(UUID id) {
    return repository.find(Validate.requireNonNull(id, "id"));
  }
}
