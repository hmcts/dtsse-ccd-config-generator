package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore;
import uk.gov.hmcts.ccd.sdk.bundling.managed.BundleDocumentStore.StoredDocument;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleView;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleView.Lifecycle;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundles;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * Library-owned selection, publication journal and current pointer; remote calls run outside locks.
 */
public class ManagedCaseBundles
    implements CaseBundles, BundleDocumentSelector, BundleJobCompletionHandler {
  private final NamedParameterJdbcTemplate jdbc;
  private final OutboxBundleJobService jobs;
  private final BundleDocumentStore store;
  private final SystemEventExecutor events;
  private final Map<String, CaseBundleDefinition> definitions;
  private final TransactionTemplate transaction;
  private final TransactionTemplate snapshot;
  private final BundleJobJson json = new BundleJobJson();
  private final Object invalidations = new Object();

  public ManagedCaseBundles(
      NamedParameterJdbcTemplate jdbc,
      OutboxBundleJobService jobs,
      BundleDocumentStore store,
      SystemEventExecutor events,
      PlatformTransactionManager transactions,
      List<CaseBundleDefinition> definitions) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.store = store;
    this.events = events;
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
    transaction = new TransactionTemplate(transactions);
    snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public static String key(String definition, String caseKey) {
    return "managed:" + definition + ":" + caseKey;
  }

  @Override
  public void invalidate(String definition, String caseKey) {
    definition(definition);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Bundle invalidation must join the source-changing transaction");
    }
    @SuppressWarnings("unchecked")
    Set<String> changed =
        (Set<String>) TransactionSynchronizationManager.getResource(invalidations);
    if (changed == null) {
      changed = new HashSet<>();
      TransactionSynchronizationManager.bindResource(invalidations, changed);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              TransactionSynchronizationManager.unbindResourceIfPossible(invalidations);
            }
          });
    }
    if (!changed.add(key(definition, caseKey))) {
      return;
    }
    jdbc.update(
        """
        insert into bundling.case_bundle(definition, case_key, requested_revision)
        values (:definition, :caseKey, 1)
        on conflict (definition, case_key) do update
          set requested_revision = case_bundle.requested_revision + 1
        """,
        Map.of("definition", definition, "caseKey", caseKey));
    jobs.submitCoalesced(
        key(definition, caseKey),
        Map.of("definition", definition, "caseKey", caseKey),
        BundleExecutionContext.empty());
  }

  @Override
  public void refresh(String definition, String caseKey) {
    transaction.executeWithoutResult(status -> invalidate(definition, caseKey));
  }

  @Override
  public BundleRequest select(BundleJobContext context) {
    String name = context.parameters().get("definition");
    String caseKey = context.parameters().get("caseKey");
    CaseBundleDefinition definition = definition(name);
    Map<String, Object> parameters =
        Map.of("definition", name, "caseKey", caseKey, "jobId", context.externalId());
    // The low-level worker can requeue an incomplete publication without a source change.
    // Give that fresh selection a revision too, so it can replace the placeholder PDF.
    transaction.executeWithoutResult(
        status ->
            jdbc.update(
                """
                update bundling.case_bundle set requested_revision = requested_revision + 1
                where definition = :definition and case_key = :caseKey
                  and requested_revision = published_revision and job_id <> :jobId
                """,
                parameters));
    return snapshot.execute(
        status -> {
          long revision =
              jdbc.queryForObject(
                  """
                  select requested_revision from bundling.case_bundle
                  where definition = :definition and case_key = :caseKey
                  """,
                  parameters,
                  Long.class);
          SelectedCaseBundle selection = definition.select(caseKey, context.externalId());
          jdbc.update(
              """
          insert into bundling.bundle_selection(job_id, attempt, definition, case_key, revision, selection)
          select external_id, :attempt, :definition, :caseKey, :revision, :selection::jsonb
          from bundling.bundle_job where external_id = :jobId and state = 'IN_PROGRESS'
            and attempts = :attempt and lease_expires_at > clock_timestamp()
          on conflict (job_id, attempt) do nothing
              """,
              Map.of(
                  "definition",
                  name,
                  "caseKey",
                  caseKey,
                  "revision",
                  revision,
                  "selection",
                  json.write(selection),
                  "jobId",
                  context.externalId(),
                  "attempt",
                  context.attempt()));
          return selection.request();
        });
  }

  @Override
  public Object onCompleted(
      BundleJob job, BundleJobContext context, BundleRequest request, BundleResult result)
      throws Exception {
    requireLease(job);
    Selection selection = selection(job.externalId(), job.attempts());
    StoredDocument uploaded = store.upload(selection.value(), result.artifact());
    BundleJobReport report = BundleJobReport.of(request, result);
    // Journal even a late upload. It must not become an attached, untracked orphan.
    jdbc.update(
        """
        insert into bundling.bundle_artifact(document_id, job_id, attempt, document, report)
        values (:id, :jobId, :attempt, :document::jsonb, :report::jsonb)
        """,
        Map.of(
            "id",
            uploaded.id(),
            "jobId",
            job.externalId(),
            "attempt",
            job.attempts(),
            "document",
            json.write(uploaded),
            "report",
            json.write(report)));
    return publish(job, new Artifact(uploaded, report, selection, false, false, false));
  }

  @Override
  public Optional<CompletedBundleJob> resume(BundleJob job, BundleJobContext context) {
    List<Artifact> uploaded =
        jdbc.query(
            """
            select a.document::text, a.report::text, a.attached, a.published, a.deleted,
                   s.definition, s.case_key, s.revision, s.selection::text
            from bundling.bundle_artifact a
            join bundling.bundle_selection s on s.job_id = a.job_id and s.attempt = a.attempt
            where a.job_id = :jobId
            order by a.created_at desc limit 1
            """,
            Map.of("jobId", job.externalId()),
            this::artifact);
    return uploaded.stream()
        .findFirst()
        .map(
            artifact ->
                new CompletedBundleJob(
                    artifact.report(),
                    artifact.deleted() ? Map.of("stored", false) : publish(job, artifact)));
  }

  @Override
  public boolean retryable(Exception failure) {
    return !(failure instanceof IllegalArgumentException)
        && (!(failure instanceof feign.FeignException remote)
            || remote.status() < 0
            || remote.status() >= 500
            || remote.status() == 429);
  }

  private Object publish(BundleJob job, Artifact artifact) {
    requireLease(job);
    if (!artifact.attached()) {
      store.attach(artifact.selection().value(), artifact.document());
      requireLease(job);
      jdbc.update(
          "update bundling.bundle_artifact set attached = true where document_id = :id",
          Map.of("id", artifact.document().id()));
    }
    if (!artifact.published()) {
      events.execute(
          artifact.selection().value().caseReference(),
          job.externalId(),
          context -> {
            lockLease(job);
            Selection selection = artifact.selection();
            Map<String, Object> parameters =
                Map.of(
                    "definition",
                    selection.definition(),
                    "caseKey",
                    selection.caseKey(),
                    "revision",
                    selection.revision(),
                    "document",
                    json.write(artifact.document()),
                    "report",
                    json.write(artifact.report()),
                    "jobId",
                    job.externalId());
            // Lock only for the short local event; invalidations and competing publishers serialize
            // here.
            List<UUID> previous =
                jdbc.query(
                    """
                    select (document->>'id')::uuid from bundling.case_bundle
                    where definition = :definition and case_key = :caseKey for update
                    """,
                    parameters,
                    (rs, n) -> rs.getObject(1, UUID.class));
            int changed =
                jdbc.update(
                    """
                update bundling.case_bundle
                set published_revision = :revision, job_id = :jobId, document = :document::jsonb,
                    report = :report::jsonb, published_at = clock_timestamp()
                where definition = :definition and case_key = :caseKey and published_revision < :revision
                """,
                    parameters);
            if (changed == 1) {
              jdbc.update(
                  "update bundling.bundle_artifact set published = true where document_id = :id",
                  Map.of("id", artifact.document().id()));
              previous.stream()
                  .filter(id -> id != null && !id.equals(artifact.document().id()))
                  .forEach(this::cleanup);
            } else {
              cleanup(artifact.document().id());
            }
            return SystemEventResult.withoutStateTransition(
                "caseBundleGenerated",
                "Case bundle generated",
                changed == 1
                    ? "Case bundle generated: " + artifact.report().pageCount() + " pages"
                    : "An older case bundle was discarded");
          });
    }
    boolean current =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                select job_id = :jobId from bundling.case_bundle
                where definition = :definition and case_key = :caseKey
                """,
                Map.of(
                    "jobId",
                    job.externalId(),
                    "definition",
                    artifact.selection().definition(),
                    "caseKey",
                    artifact.selection().caseKey()),
                Boolean.class));
    return Map.of("stored", current);
  }

  @Override
  public CaseBundleView view(String definition, String caseKey) {
    definition(definition);
    return jdbc
        .query(
            """
            select b.*, j.state as job_state
            from bundling.case_bundle b
            left join lateral (
              select state from bundling.bundle_job where coalesce_key = :key
              order by (state = 'QUEUED' and attempts = 0) desc,
                       claimed_at desc nulls last, created_at desc, external_id limit 1
            ) j on true
            where b.definition = :definition and b.case_key = :caseKey
            """,
            Map.of("definition", definition, "caseKey", caseKey, "key", key(definition, caseKey)),
            (rs, n) -> {
              Optional<CaseBundleView.Publication> publication = Optional.empty();
              if (rs.getString("document") != null) {
                StoredDocument document =
                    json.read(rs.getString("document"), StoredDocument.class, "document");
                publication =
                    Optional.of(
                        new CaseBundleView.Publication(
                            Document.builder()
                                .url(document.url())
                                .binaryUrl(document.binaryUrl())
                                .filename(document.fileName())
                                .build(),
                            rs.getTimestamp("published_at").toInstant(),
                            json.read(rs.getString("report"), BundleJobReport.class, "report")));
              }
              boolean dirty = rs.getLong("requested_revision") > rs.getLong("published_revision");
              Lifecycle lifecycle =
                  !dirty
                      ? Lifecycle.UP_TO_DATE
                      : "FAILED".equals(rs.getString("job_state"))
                          ? Lifecycle.FAILED
                          : publication.isPresent() ? Lifecycle.UPDATING : Lifecycle.PREPARING;
              return new CaseBundleView(publication, lifecycle);
            })
        .stream()
        .findFirst()
        .orElseGet(() -> new CaseBundleView(Optional.empty(), Lifecycle.NONE));
  }

  /**
   * Retry cleanup after failures and recover artifacts belonging to exhausted/obsolete attempts.
   */
  @Scheduled(fixedDelayString = "${ccd.bundling.managed.cleanup-delay:60000}")
  public void cleanUp() {
    jdbc.update(
        """
      insert into bundling.bundle_cleanup(document_id)
      select a.document_id from bundling.bundle_artifact a
      join bundling.bundle_job j on j.external_id = a.job_id
      where not a.published and not a.deleted and j.state in ('FAILED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS')
      on conflict do nothing
        """,
        Map.of());
    List<UUID> pending =
        jdbc.query(
            """
            select document_id from bundling.bundle_cleanup
            where next_attempt_at <= clock_timestamp() order by next_attempt_at limit 20
            """,
            Map.of(),
            (rs, n) -> rs.getObject(1, UUID.class));
    for (UUID id : pending) {
      try {
        store.delete(id);
        transaction.executeWithoutResult(
            status -> {
              jdbc.update(
                  "delete from bundling.bundle_cleanup where document_id = :id", Map.of("id", id));
              jdbc.update(
                  "update bundling.bundle_artifact set deleted = true where document_id = :id",
                  Map.of("id", id));
            });
      } catch (RuntimeException failure) {
        jdbc.update(
              """
          update bundling.bundle_cleanup set next_attempt_at = clock_timestamp() + interval '1 minute'
          where document_id = :id
            """,
            Map.of("id", id));
      }
    }
  }

  private void cleanup(UUID id) {
    jdbc.update(
        "insert into bundling.bundle_cleanup(document_id) values (:id) on conflict do nothing",
        Map.of("id", id));
  }

  private void requireLease(BundleJob job) {
    boolean held =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
            select state = 'IN_PROGRESS' and attempts = :attempt and lease_expires_at > clock_timestamp()
            from bundling.bundle_job where external_id = :jobId
            """,
                Map.of("jobId", job.externalId(), "attempt", job.attempts()),
                Boolean.class));
    if (!held) {
      throw new LostLease();
    }
  }

  private void lockLease(BundleJob job) {
    jdbc.queryForObject(
        "select external_id from bundling.bundle_job where external_id = :jobId for update",
        Map.of("jobId", job.externalId()),
        UUID.class);
    requireLease(job);
  }

  private Selection selection(UUID id, int attempt) {
    return jdbc.queryForObject(
        """
        select definition, case_key, revision, selection::text from bundling.bundle_selection
        where job_id = :jobId and attempt = :attempt
        """,
        Map.of("jobId", id, "attempt", attempt),
        (rs, n) -> selection(rs));
  }

  private Selection selection(ResultSet rs) throws SQLException {
    return new Selection(
        rs.getString("definition"),
        rs.getString("case_key"),
        rs.getLong("revision"),
        json.read(rs.getString("selection"), SelectedCaseBundle.class, "selection"));
  }

  private Artifact artifact(ResultSet rs, int index) throws SQLException {
    return new Artifact(
        json.read(rs.getString("document"), StoredDocument.class, "document"),
        json.read(rs.getString("report"), BundleJobReport.class, "report"),
        selection(rs),
        rs.getBoolean("attached"),
        rs.getBoolean("published"),
        rs.getBoolean("deleted"));
  }

  private CaseBundleDefinition definition(String name) {
    CaseBundleDefinition definition = definitions.get(name);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown case bundle definition: " + name);
    }
    return definition;
  }

  private record Selection(
      String definition, String caseKey, long revision, SelectedCaseBundle value) {}

  private record Artifact(
      StoredDocument document,
      BundleJobReport report,
      Selection selection,
      boolean attached,
      boolean published,
      boolean deleted) {}

  private static final class LostLease extends RuntimeException {
    private LostLease() {
      super("The bundle publication attempt no longer holds its lease");
    }
  }
}
