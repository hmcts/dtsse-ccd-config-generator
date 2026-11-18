package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * JDBC access to bundling.bundle_job, the queue with one row per bundle, and to bundling.bundle,
 * which describes the bundles rendered from it. A change queues the bundle's row, even while a
 * worker is rendering it; the worker's completion then leaves the row queued, so the bundle is
 * rendered again and a case has one render of a bundle at a time. Claiming uses FOR UPDATE SKIP
 * LOCKED plus a lease, and every worker-side write is guarded by the lease owner, so a worker that
 * lost its lease gets false back and the lease holder's state stands.
 */
public class BundleJobRepository {
  private static final String JOB_COLUMNS = """
      select external_id, state, attempts, queued_at, updated_at, definition, case_reference,
             failure_code, failure_message,
             failure_documents::text as failure_documents
      from bundling.bundle_job
      """;

  private static final String BUNDLE_COLUMNS = """
      select id, definition, case_reference, created_at, outcome, file_name,
             media_type, size_bytes, sha256, page_count, request::text as request,
             documents::text as documents, missing_documents::text as missing_documents,
             warnings::text as warnings, document_id, document_url, binary_url
      from bundling.bundle
      """;
  // Queues an existing row. Its lease, if a worker is rendering it, is left to that worker.
  private static final String QUEUE_AGAIN = """
      state = 'QUEUED', attempts = 0, next_attempt_at = null, unavailable_requeues = 0,
      priority = excluded.priority, failure_code = null, failure_message = null,
      failure_documents = null, transient_history = '[]'::jsonb, updated_at = now(),
      queued_at = case when bundle_job.state = 'QUEUED' then bundle_job.queued_at else now() end
      """;
  private static final TypeReference<List<DocumentResult>> DOCUMENT_RESULTS =
      new TypeReference<>() {
      };
  private static final TypeReference<List<MissingDocument>> MISSING_DOCUMENTS =
      new TypeReference<>() {
      };
  private static final TypeReference<List<BundleWarning>> WARNINGS = new TypeReference<>() {
  };

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final BundleJobJson json = new BundleJobJson();

  public BundleJobRepository(NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
  }

  /**
   * Holds the case until the transaction ends, and says whether it exists. Every write here that
   * adds a row for a case holds it first, as an event does, so the write waits for an event on the
   * case or for its deletion instead of deadlocking with either.
   */
  boolean holdCase(long caseReference) {
    return jdbc.query("""
        select 1 from ccd.case_data where reference = :caseReference for key share
        """, params("caseReference", caseReference), ResultSet::next);
  }

  /** Runs the writes in a transaction that holds the case, or returns null if the case is gone. */
  <T> T holdingCase(long caseReference, Supplier<T> writes) {
    return transaction.execute(status -> holdCase(caseReference) ? writes.get() : null);
  }

  /**
   * Queues the bundle for a change to its documents, and returns its row's id. The caller holds
   * the row until it commits and the claim passes over a held row, so a bundle is never claimed
   * before the change that queued it is visible. A change makes a waiting retry or re-run
   * immediate and a waiting refresh live.
   */
  UUID changed(String definition, long caseReference) {
    return jdbc.queryForObject("""
        insert into bundling.bundle_job (definition, case_reference)
        values (:definition, :caseReference)
        on conflict (case_reference, definition) do update set
        """ + QUEUE_AGAIN + " returning external_id",
        params("definition", definition, "caseReference", caseReference), UUID.class);
  }

  /** The case as CCD holds it: where a bundle of the case is stored. */
  SelectedCaseBundle selectedFor(long caseReference, BundleRequest request) {
    return jdbc.queryForObject("""
        select case_type_id, jurisdiction from ccd.case_data where reference = :caseReference
        """, params("caseReference", caseReference),
        (rs, n) -> new SelectedCaseBundle(caseReference, rs.getString("case_type_id"),
            rs.getString("jurisdiction"), request));
  }

  /** Records the refresh key, and says whether it was new. A concurrent caller waits its turn. */
  boolean recordRefresh(String key) {
    return jdbc.update("""
        insert into bundling.refresh_key (key) values (:key) on conflict do nothing
        """, params("key", key)) == 1;
  }

  /**
   * Queues a background render of the definition's bundle for every case of the case type, and
   * returns how many it queued. A bundle already queued is left as it is. It holds each case
   * before queuing its bundle, as {@link #holdingCase} does, so events on a case it has reached
   * wait until it commits.
   */
  int refresh(String definition, String caseType) {
    return jdbc.update("""
        insert into bundling.bundle_job (definition, case_reference, priority)
        select :definition, reference, -1 from ccd.case_data
        where case_type_id = :caseType order by reference for key share
        on conflict (case_reference, definition) do update set
        """ + QUEUE_AGAIN + " where bundle_job.state <> 'QUEUED'",
        params("definition", definition, "caseType", caseType));
  }

  public Optional<BundleJob> find(UUID externalId) {
    return jdbc.query(JOB_COLUMNS + " where external_id = :externalId",
        params("externalId", externalId), (rs, n) -> map(rs)).stream().findFirst();
  }

  /** The bundle's queue row, if the bundle has ever been queued. */
  public Optional<BundleJob> find(String definition, long caseReference) {
    return jdbc.query(JOB_COLUMNS + """
         where definition = :definition and case_reference = :caseReference
        """, params("definition", definition, "caseReference", caseReference), (rs, n) -> map(rs))
        .stream().findFirst();
  }

  /**
   * Claims the next bundles: those queued and due, those completed and due a re-run, and those
   * whose worker's lease expired. A bundle queued again while a worker renders it waits for that
   * worker's lease. A worker claims, and gives up on, only its own definitions, so a pod on an
   * older release leaves a definition it does not have to the pods that do.
   */
  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts, Collection<String> definitions) {
    if (definitions.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        with claimable as (
          select external_id from bundling.bundle_job
          where (state in ('QUEUED', 'IN_PROGRESS') or next_attempt_at is not null)
            and attempts < :maxAttempts
            and definition in (:definitions)
            and (lease_expires_at is null or lease_expires_at <= now())
            and (state = 'IN_PROGRESS' or next_attempt_at <= now()
                 or (state = 'QUEUED' and next_attempt_at is null))
          order by priority desc, queued_at, external_id
          limit :limit
          for update skip locked
        )
        update bundling.bundle_job job
        set state = 'IN_PROGRESS', attempts = job.attempts + 1, lease_owner = :leaseOwner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null, updated_at = now()
        from claimable where job.external_id = claimable.external_id
        returning job.external_id, job.attempts, job.queued_at, job.updated_at,
            job.definition, job.case_reference,
            job.unavailable_requeues, job.transient_history::text as transient_history
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", leaseDuration.toMillis(),
            "maxAttempts", maxAttempts, "definitions", definitions),
        (rs, n) -> new ClaimedBundleJob(
            new BundleJob(rs.getObject("external_id", UUID.class), BundleJobState.IN_PROGRESS,
                rs.getInt("attempts"), instant(rs, "queued_at"), instant(rs, "updated_at"),
                rs.getString("definition"), rs.getLong("case_reference"), Optional.empty()),
            rs.getInt("unavailable_requeues"), rs.getString("transient_history"), leaseOwner));
  }

  /** Fails the bundles whose worker's lease expired on their last attempt. */
  List<UUID> failExhaustedStaleJobs(int maxAttempts, Collection<String> definitions) {
    if (definitions.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        update bundling.bundle_job
        set state = 'FAILED', failure_code = :code, failure_documents = '[]'::jsonb,
            failure_message = 'The job was claimed ' || attempts || ' time(s) without recording a '
                || 'result and each lease expired; the attempt bound (' || :maxAttempts || ') is '
                || 'exhausted. The render most likely crashed; see the service logs.',
            lease_owner = null, lease_expires_at = null, next_attempt_at = null, updated_at = now()
        where state = 'IN_PROGRESS' and lease_expires_at <= now() and attempts >= :maxAttempts
          and definition in (:definitions)
        returning external_id
        """,
        params("maxAttempts", maxAttempts, "code", BundleErrorCode.ASSEMBLY_FAILED.name(),
            "definitions", definitions),
        (rs, n) -> rs.getObject("external_id", UUID.class));
  }

  /**
   * Records the bundle a worker rendered and releases its lease, in one statement. The row is
   * completed unless a change queued it again meanwhile, in which case it stays queued. A re-run
   * time makes a completed bundle claimable again then. Nothing is written by a worker that lost
   * its lease.
   */
  boolean markCompleted(UUID externalId, BundleJobState state, BundleRecord bundle,
      String leaseOwner, Instant rerunAt) {
    return jdbc.update("""
        with completed as (
          update bundling.bundle_job
          set state = case when state = 'IN_PROGRESS' then :state else state end,
              next_attempt_at = case when state = 'IN_PROGRESS' then :rerunAt::timestamptz end,
              unavailable_requeues = case
                when state <> 'IN_PROGRESS' then unavailable_requeues
                when :rerunAt::timestamptz is null then 0
                else unavailable_requeues + 1 end,
              attempts = case
                when state = 'IN_PROGRESS' and :rerunAt::timestamptz is null then attempts
                else 0 end,
              lease_owner = null, lease_expires_at = null, updated_at = now()
          where external_id = :externalId and lease_owner = :leaseOwner
          returning definition, case_reference
        )
        insert into bundling.bundle (definition, case_reference, outcome,
          file_name, media_type, size_bytes, sha256, page_count, request, documents,
          missing_documents, warnings, document_id, document_url, binary_url)
        select definition, case_reference, :outcome, :fileName, :mediaType, :size,
          :sha256, :pageCount, :request::jsonb, :documents::jsonb, :missingDocuments::jsonb,
          :warnings::jsonb, :documentId, :documentUrl, :binaryUrl
        from completed
        """,
        params("externalId", externalId, "state", state.name(), "leaseOwner", leaseOwner,
            "rerunAt", rerunAt == null ? null : OffsetDateTime.ofInstant(rerunAt, ZoneOffset.UTC),
            "outcome", bundle.outcome(), "fileName", bundle.fileName(),
            "mediaType", bundle.mediaType(), "size", bundle.size(), "sha256", bundle.sha256(),
            "pageCount", bundle.pageCount(), "request", bundle.requestJson(),
            "documents", bundle.documentsJson(), "missingDocuments", bundle.missingDocumentsJson(),
            "warnings", bundle.warningsJson(),
            "documentId", bundle.stored() == null ? null : bundle.stored().id(),
            "documentUrl", bundle.stored() == null ? null : bundle.stored().url(),
            "binaryUrl", bundle.stored() == null ? null : bundle.stored().binaryUrl())) == 1;
  }

  /** A rendered bundle, serialised for bundling.bundle. */
  record BundleRecord(String outcome, String fileName, String mediaType, long size, String sha256,
      int pageCount, String requestJson, String documentsJson, String missingDocumentsJson,
      String warningsJson, StoredBundleDocument stored) {
  }

  /** The case's bundle recorded last: its renders run one at a time, so that is the current one. */
  public Optional<BundleMetadata> findLatestBundle(String definition, long caseReference) {
    return jdbc.query(BUNDLE_COLUMNS + """
         where definition = :definition and case_reference = :caseReference
         order by created_at desc, id
         limit 1
        """, params("definition", definition, "caseReference", caseReference), (rs, n) -> mapBundle(rs))
        .stream().findFirst();
  }

  /**
   * Stored PDFs of bundles that a bundle recorded later for the same case replaced at least
   * {@code replacedFor} ago.
   */
  List<UUID> findReplacedDocuments(Duration replacedFor, int limit) {
    return jdbc.query("""
        select document_id from bundling.bundle old
        where document_id is not null and deleted_at is null
          and exists (select 1 from bundling.bundle newer
                      where newer.definition = old.definition and newer.case_reference = old.case_reference
                        and newer.created_at > old.created_at
                        and newer.created_at <= now() - (:replacedMillis * interval '1 millisecond'))
        order by created_at
        limit :limit
        """, params("limit", limit, "replacedMillis", replacedFor.toMillis()),
        (rs, n) -> rs.getObject("document_id", UUID.class));
  }

  void markDocumentDeleted(UUID documentId) {
    jdbc.update("update bundling.bundle set deleted_at = now() where document_id = :documentId",
        params("documentId", documentId));
  }

  /** Fails the bundle, unless a change queued it again meanwhile: then it is rendered again. */
  boolean markFailed(UUID externalId, BundleErrorCode code, String message,
      String documentFailuresJson, String transientHistoryJson, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = case when state = 'IN_PROGRESS' then 'FAILED' else state end,
            failure_code = case when state = 'IN_PROGRESS' then :code end,
            failure_message = case when state = 'IN_PROGRESS' then :message end,
            failure_documents = case when state = 'IN_PROGRESS' then :documentFailures::jsonb end,
            transient_history = case when state = 'IN_PROGRESS'
              then coalesce(:transientHistory::jsonb, transient_history) else transient_history end,
            lease_owner = null, lease_expires_at = null, next_attempt_at = null,
            updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner
        """,
        params("externalId", externalId, "code", code.name(), "message", message,
            "documentFailures", documentFailuresJson, "transientHistory", transientHistoryJson,
            "leaseOwner", leaseOwner)) == 1;
  }

  /** Queues another attempt, at once if a change queued the bundle again meanwhile. */
  boolean requeueForRetry(UUID externalId, Instant nextAttemptAt, String transientHistoryJson,
      String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set next_attempt_at = case when state = 'IN_PROGRESS' then :nextAttemptAt::timestamptz end,
            transient_history = case when state = 'IN_PROGRESS'
              then :transientHistory::jsonb else transient_history end,
            state = 'QUEUED', lease_owner = null, lease_expires_at = null, updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner
        """,
        params("externalId", externalId,
            "nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
            "transientHistory", transientHistoryJson, "leaseOwner", leaseOwner)) == 1;
  }

  private static MapSqlParameterSource params(Object... keysAndValues) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.addValue((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return params;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private BundleMetadata mapBundle(ResultSet rs) throws SQLException {
    UUID documentId = rs.getObject("document_id", UUID.class);
    Optional<StoredBundleDocument> stored = documentId == null ? Optional.empty()
        : Optional.of(new StoredBundleDocument(documentId, rs.getString("document_url"),
            rs.getString("binary_url")));
    return new BundleMetadata(rs.getObject("id", UUID.class), rs.getString("definition"),
        rs.getLong("case_reference"), instant(rs, "created_at"),
        json.read(rs.getString("request"), BundleRequest.class, "bundle request"),
        BundleOutcome.valueOf(rs.getString("outcome")), rs.getString("file_name"),
        rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
        rs.getInt("page_count"),
        json.read(rs.getString("documents"), DOCUMENT_RESULTS, "bundle documents"),
        json.read(rs.getString("missing_documents"), MISSING_DOCUMENTS, "missing documents"),
        json.read(rs.getString("warnings"), WARNINGS, "bundle warnings"), stored);
  }

  private BundleJob map(ResultSet rs) throws SQLException {
    String failureCode = rs.getString("failure_code");
    Optional<BundleJobFailure> failure = failureCode == null ? Optional.empty()
        : Optional.of(new BundleJobFailure(BundleErrorCode.valueOf(failureCode),
            rs.getString("failure_message"),
            json.readDocumentFailures(rs.getString("failure_documents"))));
    return new BundleJob(rs.getObject("external_id", UUID.class),
        BundleJobState.valueOf(rs.getString("state")), rs.getInt("attempts"),
        instant(rs, "queued_at"), instant(rs, "updated_at"),
        rs.getString("definition"), rs.getLong("case_reference"), failure);
  }
}
