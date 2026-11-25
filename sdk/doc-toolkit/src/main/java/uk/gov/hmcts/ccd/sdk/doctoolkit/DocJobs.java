package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Background rendering. Spring Boot services get one when {@code ccd.doc-toolkit.jobs.enabled}
 * is true.
 *
 * <p>One-off documents (letters, orders) are queued with {@link #submit}. Documents kept up to
 * date with their case are declared with a {@link CaseDocDefinition} and queued by case events;
 * {@link #status} says where one is.
 */
public final class DocJobs {
  private final DocJobRepository repository;

  /** Created by the auto-configuration. */
  public DocJobs(DocJobRepository repository) {
    this.repository = Validate.requireNonNull(repository, "repository");
  }

  /**
   * Queues a one-off document in the caller's transaction, so it is queued only if that commits.
   * A worker renders it, stores it in CDAM, attaches it to the case and hands it to the service's
   * {@link DocJobHandler}.
   *
   * @throws IllegalArgumentException if a part is a local file, which the instance running the
   *     job may not have; queue its content with {@link DocSource#bytes} instead
   */
  public DocJob submit(PdfRequest request, long caseReference) {
    Validate.requireNonNull(request, "request");
    UUID id = UUID.randomUUID();
    repository.insertOneOff(id, caseReference, DocRequestJson.write(request));
    return repository.find(id).orElseThrow();
  }

  /** A job still in flight, by id. One-off jobs are deleted once handled. */
  public Optional<DocJob> find(UUID id) {
    return repository.find(Validate.requireNonNull(id, "id"));
  }

  /** Where a case's kept-up-to-date document is; empty if it has never had one. */
  public Optional<CaseDocStatus> status(String key, long caseReference) {
    return repository.status(caseReference, Validate.requireNonBlank(key, "key"));
  }
}
