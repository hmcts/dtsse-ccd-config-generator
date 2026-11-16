package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleView;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleView.Lifecycle;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundles;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * Keeps each case's bundle current on the job outbox. A change queues a coalesced job; the worker
 * selects the documents afresh, renders, and this class stores the PDF on the case. The worker
 * records it in bundling.bundle, where the bundle selected last is the case's current one, and
 * {@link #cleanUp} deletes the PDFs it replaced.
 */
@Slf4j
public class ManagedCaseBundles
    implements CaseBundles, BundleDocumentSelector, BundleJobCompletionHandler {
  // How long a replaced bundle's PDF is kept, so a link a reader already has keeps working.
  static final Duration REPLACED_PDF_KEPT_FOR = Duration.ofHours(12);

  private final BundleJobRepository repository;
  private final BundleDocumentStore store;
  private final Map<String, CaseBundleDefinition> definitions;
  private final TransactionTemplate snapshot;

  public ManagedCaseBundles(
      BundleJobRepository repository,
      BundleDocumentStore store,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions) {
    this.repository = repository;
    this.store = store;
    this.definitions =
        definitions.stream()
            .collect(Collectors.toUnmodifiableMap(CaseBundleDefinition::name, Function.identity()));
    snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void changed(String definition, String caseKey) {
    definition(definition);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "A bundle change must join the transaction that changed its documents");
    }
    // Calls in one transaction join the job its first call queued.
    repository.insertOrJoin(UUID.randomUUID(), definition, caseKey, null, 0);
  }

  @Override
  public BundleRequest select(BundleJobContext context) {
    CaseBundleDefinition definition = definition(context.definition());
    // One snapshot, so the documents and whatever decides their visibility agree.
    SelectedCaseBundle selected = snapshot.execute(status -> definition.select(context.caseKey()));
    // The job records which case it rendered for: the bundle row's case reference, and where
    // onCompleted attaches the PDF.
    repository.recordCase(context.externalId(), selected.caseReference(), selected.caseType(),
        selected.jurisdiction());
    return selected.request().withExternalId(context.externalId());
  }

  @Override
  public Object onCompleted(
      BundleJob job, BundleJobContext context, BundleRequest request, BundleResult result)
      throws Exception {
    BundleJobRepository.CaseTarget target = repository.findCase(job.externalId());
    return store.store(new SelectedCaseBundle(target.caseReference(), target.caseType(),
        target.jurisdiction(), request), result.artifact());
  }

  @Override
  public boolean retryable(Exception failure) {
    return !(failure instanceof IllegalArgumentException)
        && (!(failure instanceof feign.FeignException remote)
            || remote.status() < 0
            || remote.status() >= 500
            || remote.status() == 429);
  }

  @Override
  public void discarded(BundleJob job, Object summary) {
    if (summary instanceof StoredBundleDocument stored) {
      store.delete(stored.id());
    }
  }

  @Override
  public CaseBundleView view(String definition, String caseKey) {
    definition(definition);
    // The job first: a bundle recorded in between then reads as updating, never as up to date.
    Optional<BundleJob> job = repository.findLatest(definition, caseKey);
    Optional<BundleMetadata> current =
        repository.findLatestBundle(definition, caseKey).filter(bundle -> bundle.stored().isPresent());
    boolean published = current.isPresent();
    Lifecycle lifecycle =
        job.map(
                latest ->
                    switch (latest.state()) {
                      case QUEUED, IN_PROGRESS ->
                          published ? Lifecycle.UPDATING : Lifecycle.PREPARING;
                      case FAILED -> Lifecycle.FAILED;
                      case COMPLETED, COMPLETED_WITH_WARNINGS ->
                          published ? Lifecycle.UP_TO_DATE : Lifecycle.NONE;
                    })
            .orElse(published ? Lifecycle.UP_TO_DATE : Lifecycle.NONE);
    return new CaseBundleView(current, lifecycle);
  }

  /** Deletes the PDFs of bundles that a later bundle of the same case replaced 12 hours ago. */
  @Scheduled(fixedDelayString = "${ccd.bundling.job.cleanup-delay:60000}")
  public void cleanUp() {
    for (UUID id : repository.findReplacedDocuments(REPLACED_PDF_KEPT_FOR, 20)) {
      try {
        store.delete(id);
        repository.markDocumentDeleted(id);
      } catch (RuntimeException failure) {
        log.warn("Replaced bundle document {} could not be deleted; it is retried", id, failure);
      }
    }
  }

  private CaseBundleDefinition definition(String name) {
    CaseBundleDefinition definition = definitions.get(name);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown case bundle definition: " + name);
    }
    return definition;
  }
}
