package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;

/**
 * Where a case's kept-up-to-date documents are. Spring Boot services get one when
 * {@code ccd.doc-toolkit.jobs.enabled} is true; documents are declared with a
 * {@link CaseDocDefinition} and queued by case events.
 */
public final class DocJobs {
  private final DocJobRepository repository;

  /** Created by the auto-configuration. */
  public DocJobs(DocJobRepository repository) {
    this.repository = Validate.requireNonNull(repository, "repository");
  }

  /** Where a case's kept-up-to-date document is; empty if it has none. */
  public Optional<CaseDocStatus> status(String key, long caseReference) {
    return repository.status(caseReference, Validate.requireNonBlank(key, "key"));
  }
}
