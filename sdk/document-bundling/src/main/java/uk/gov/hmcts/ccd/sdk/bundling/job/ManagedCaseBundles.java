package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Keeps each case's bundle current on the job queue. A change queues the bundle; the worker
 * selects the documents afresh, renders, and this class stores the PDF on the case. The worker
 * records it in bundling.bundle, where the bundle rendered last is the case's current one, and
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
  private final TransactionTemplate transaction;
  private final String refreshKey;
  private volatile boolean refreshChecked;

  public ManagedCaseBundles(
      BundleJobRepository repository,
      BundleDocumentStore store,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions,
      String refreshKey) {
    this.refreshKey = refreshKey;
    this.repository = repository;
    this.store = store;
    this.definitions =
        definitions.stream()
            .collect(Collectors.toUnmodifiableMap(CaseBundleDefinition::name, Function.identity()));
    transaction = new TransactionTemplate(transactions);
    snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void changed(String definition, long caseReference) {
    definition(definition);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "A bundle change must join the transaction that changed its documents");
    }
    // The event that creates a case has no case to hold yet.
    repository.holdCase(caseReference);
    repository.changed(definition, caseReference);
  }

  /**
   * Refreshes for the configured ccd.bundling.refresh, off the startup path. A failure is logged by
   * the scheduler and tried again a minute later.
   */
  @Scheduled(fixedDelay = 60_000)
  public void refreshIfNew() {
    if (!refreshChecked) {
      refresh(refreshKey);
      refreshChecked = true;
    }
  }

  /**
   * Queues a background render of every case's bundles, once for each key, and says whether this
   * call did. The key commits with each definition's jobs, so other pods and restarts skip it.
   */
  public boolean refresh(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    boolean refreshed = false;
    for (CaseBundleDefinition definition : definitions.values()) {
      refreshed |= transaction.execute(status -> {
        if (!repository.recordRefresh(key, definition.name())) {
          return false;
        }
        log.info("Bundle refresh {} queued {} {} bundles", key,
            repository.refresh(definition.name(), definition.caseType()), definition.name());
        return true;
      });
    }
    return refreshed;
  }

  @Override
  public SelectedCaseBundle select(BundleJob job) {
    CaseBundleDefinition definition = definition(job.definition());
    // One snapshot, so the documents and whatever decides their visibility agree.
    BundleRequest request = snapshot.execute(status ->
        Objects.requireNonNull(definition.select(job.caseReference()),
            () -> definition.name() + " selected no bundle request"));
    return repository.selectedFor(job.caseReference(), request.withExternalId(job.externalId()));
  }

  @Override
  public Collection<String> definitions() {
    return definitions.keySet();
  }

  @Override
  public StoredBundleDocument onCompleted(BundleJob job, SelectedCaseBundle selected,
      BundleResult result) throws Exception {
    return store.store(selected, result.artifact());
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
  public void discarded(BundleJob job, StoredBundleDocument stored) {
    if (stored != null) {
      store.delete(stored.id());
    }
  }

  @Override
  public CaseBundleView view(String definition, long caseReference) {
    definition(definition);
    // The job first: a bundle recorded in between then reads as updating, never as up to date.
    Optional<BundleJob> job = repository.find(definition, caseReference);
    Optional<BundleMetadata> current =
        repository.findLatestBundle(definition, caseReference).filter(bundle -> bundle.stored().isPresent());
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
