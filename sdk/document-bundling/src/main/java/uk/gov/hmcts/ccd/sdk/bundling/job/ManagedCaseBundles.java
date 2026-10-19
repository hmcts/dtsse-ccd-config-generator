package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
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
  private static final String CASE_TYPE = "bundling.caseType";
  private static final String JURISDICTION = "bundling.jurisdiction";
  // How long a replaced bundle's PDF is kept, so a link a reader already has keeps working.
  static final Duration REPLACED_PDF_KEPT_FOR = Duration.ofHours(12);

  private final NamedParameterJdbcTemplate jdbc;
  private final OutboxBundleJobService jobs;
  private final BundleJobRepository repository;
  private final BundleDocumentStore store;
  private final Map<String, CaseBundleDefinition> definitions;
  private final TransactionTemplate snapshot;
  private final BundleJobJson json = new BundleJobJson();

  public ManagedCaseBundles(
      NamedParameterJdbcTemplate jdbc,
      OutboxBundleJobService jobs,
      BundleJobRepository repository,
      BundleDocumentStore store,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.repository = repository;
    this.store = store;
    this.definitions =
        definitions.stream()
            .collect(Collectors.toUnmodifiableMap(CaseBundleDefinition::name, Function.identity()));
    this.definitions
        .keySet()
        .forEach(
            name -> {
              if (!name.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException(
                    "Bundle definition name must contain only letters, digits, ., _ or -");
              }
            });
    snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public static String key(String definition, String caseKey) {
    return "managed:" + definition + ":" + caseKey;
  }

  @Override
  public void changed(String definition, String caseKey) {
    definition(definition);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "A bundle change must join the transaction that changed its documents");
    }
    // Calls in one transaction join the job its first call queued.
    jobs.submitCoalesced(
        key(definition, caseKey),
        Map.of("definition", definition, "caseKey", caseKey),
        BundleExecutionContext.empty());
  }

  @Override
  public BundleRequest select(BundleJobContext context) {
    CaseBundleDefinition definition = definition(context.parameters().get("definition"));
    String caseKey = context.parameters().get("caseKey");
    // One snapshot, so the documents and whatever decides their visibility agree.
    SelectedCaseBundle selected = snapshot.execute(status -> definition.select(caseKey));
    // The job records which case it rendered for: the bundle row's case reference, and where
    // onCompleted attaches the PDF.
    jdbc.update(
        """
        update bundling.bundle_job set execution_context = execution_context || :caseContext::jsonb
        where external_id = :jobId
        """,
        Map.of(
            "jobId",
            context.externalId(),
            "caseContext",
            json.write(
                Map.of(
                    "caseReference", String.valueOf(selected.caseReference()),
                    CASE_TYPE, selected.caseType(),
                    JURISDICTION, selected.jurisdiction()))));
    return selected.request().withExternalId(context.externalId());
  }

  @Override
  public Object onCompleted(
      BundleJob job, BundleJobContext context, BundleRequest request, BundleResult result)
      throws Exception {
    return store.store(target(job.externalId(), request), result.artifact());
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
    String key = key(definition, caseKey);
    // The job first: a bundle recorded in between then reads as updating, never as up to date.
    Optional<BundleJob> job = jobs.findLatest(key);
    Optional<BundleMetadata> current =
        jobs.findLatestBundle(key).filter(bundle -> bundle.stored().isPresent());
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
  @Scheduled(fixedDelayString = "${ccd.bundling.managed.cleanup-delay:60000}")
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

  private SelectedCaseBundle target(UUID jobId, BundleRequest request) {
    BundleExecutionContext context =
        json.read(
            jdbc.queryForObject(
                "select execution_context::text from bundling.bundle_job where external_id = :jobId",
                Map.of("jobId", jobId),
                String.class),
            BundleExecutionContext.class,
            "execution context");
    return new SelectedCaseBundle(
        Long.parseLong(context.caseReference().orElseThrow()),
        context.attributes().get(CASE_TYPE),
        context.attributes().get(JURISDICTION),
        request);
  }

  private CaseBundleDefinition definition(String name) {
    CaseBundleDefinition definition = definitions.get(name);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown case bundle definition: " + name);
    }
    return definition;
  }
}
