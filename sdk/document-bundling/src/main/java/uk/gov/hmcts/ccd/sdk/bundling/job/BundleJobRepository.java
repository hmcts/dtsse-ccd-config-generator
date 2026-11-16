package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;

/**
 * JDBC access to the bundling.bundle_job outbox table, and to bundling.bundle, which describes the
 * bundles completed jobs rendered. Inserts join the caller's transaction; worker writes are
 * statement-atomic. Coalesced submissions share a per-key advisory lock with backfill so its
 * eligibility check sees competing commits before inserting. Claiming uses
 * FOR UPDATE SKIP LOCKED plus a lease; every worker-side write is guarded by the lease owner, so a
 * worker that lost its lease gets false back and the lease holder's state stands.
 */
public class BundleJobRepository {
  private static final String IN_PROGRESS = BundleJobState.IN_PROGRESS.name();
  private static final String JOB_COLUMNS = """
      select external_id, state, attempts, created_at, updated_at, coalesce_key,
             coalesced_submissions, claimed_at, result::text as result, failure_code,
             failure_message,
             failure_documents::text as failure_documents
      from bundling.bundle_job
      """;

  private static final String BUNDLE_COLUMNS = """
      select id, job_id, bundle_key, case_reference, selected_at, created_at, outcome, file_name,
             media_type, size_bytes, sha256, page_count, request::text as request,
             documents::text as documents, missing_documents::text as missing_documents,
             warnings::text as warnings, document_id, document_url, binary_url
      from bundling.bundle
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
  private final BundleJobJson json = new BundleJobJson();

  public BundleJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the job unless its external id exists; a concurrent insert of the id waits. */
  boolean insertIfAbsent(UUID externalId, String requestJson, String selectorParametersJson,
      String executionContextJson) {
    return jdbc.update("""
        insert into bundling.bundle_job
          (external_id, state, request_version, request, selector_parameters, execution_context)
        values (:externalId, 'QUEUED', :requestVersion, :request::jsonb, :parameters::jsonb,
          :context::jsonb)
        on conflict (external_id) do nothing
        """,
        params("externalId", externalId, "requestVersion", BundleJobJson.REQUEST_VERSION,
            "request", requestJson, "parameters", selectorParametersJson,
            "context", executionContextJson)) == 1;
  }

  /**
   * Inserts a job under the coalesce key, or joins the key's job that is still waiting for its
   * first claim. Joining updates that row, so the caller holds its lock until it commits and the
   * worker's SKIP LOCKED claim passes over it: a joined job is never claimed before the change
   * that joined it is visible. If the waiting job is being claimed concurrently, Postgres waits
   * for the claim, finds the row no longer waiting, and inserts a follow-up instead.
   */
  CoalescedInsert insertOrJoin(UUID externalId, String coalesceKey,
      String selectorParametersJson, String executionContextJson) {
    return insertOrJoin(externalId, coalesceKey, selectorParametersJson, executionContextJson, null, 0);
  }

  /** Delayed follow-ups retain their priority; live changes make them immediate and promote them. */
  CoalescedInsert insertOrJoin(UUID externalId, String coalesceKey,
      String selectorParametersJson, String executionContextJson, Instant notBefore, int priority) {
    return jdbc.queryForObject("""
        with key_lock as materialized (
          select pg_advisory_xact_lock(hashtextextended('bundling.job:' || :coalesceKey, 0))
        )
        insert into bundling.bundle_job
          (external_id, coalesce_key, state, request_version, request, selector_parameters,
           execution_context, next_attempt_at, priority)
        select :externalId, :coalesceKey, 'QUEUED', :requestVersion, null, :parameters::jsonb,
          :context::jsonb, :notBefore, :priority
        from key_lock where true
        on conflict (coalesce_key)
          where coalesce_key is not null and state = 'QUEUED' and attempts = 0
        do update set coalesced_submissions = bundle_job.coalesced_submissions + 1,
          last_coalesced_at = clock_timestamp(), updated_at = clock_timestamp(),
          priority = greatest(bundle_job.priority, excluded.priority),
          next_attempt_at = case
            when bundle_job.next_attempt_at is null or excluded.next_attempt_at is null then null
            else least(bundle_job.next_attempt_at, excluded.next_attempt_at) end
        returning external_id, selector_parameters::text as selector_parameters
        """,
        params("externalId", externalId, "coalesceKey", coalesceKey,
            "requestVersion", BundleJobJson.REQUEST_VERSION, "parameters", selectorParametersJson,
            "context", executionContextJson, "priority", priority,
            "notBefore", notBefore == null ? null : OffsetDateTime.ofInstant(notBefore, ZoneOffset.UTC)),
        (rs, n) -> new CoalescedInsert(rs.getObject("external_id", UUID.class),
            rs.getString("selector_parameters")));
  }

  /** A new trigger may retry failures, but never duplicates active work or a stored publication. */
  boolean insertBackfill(UUID externalId, String coalesceKey, String parametersJson) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Backfill eligibility and insertion must share a transaction");
    }
    // Separate statements deliberately: READ COMMITTED takes a fresh snapshot after the lock wait.
    jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('bundling.job:' || :key, 0))",
        params("key", coalesceKey), (rs, n) -> null);
    // Both conflicts are expected: the per-token ID and the waiting coalesce key.
    return jdbc.update("""
        insert into bundling.bundle_job
          (external_id, coalesce_key, state, request_version, selector_parameters, execution_context, priority)
        select :externalId, :key, 'QUEUED', :version, :parameters::jsonb, '{}'::jsonb, -1
        where not exists (select 1 from bundling.bundle_job where coalesce_key = :key
                          and state in ('QUEUED', 'IN_PROGRESS'))
          and not exists (select 1 from bundling.bundle where bundle_key = :key
                          and document_id is not null and deleted_at is null)
        on conflict do nothing
        """, params("externalId", externalId, "key", coalesceKey,
            "version", BundleJobJson.REQUEST_VERSION, "parameters", parametersJson)) == 1;
  }

  /** The job a coalesced submission landed on, and the parameters that job keeps. */
  record CoalescedInsert(UUID externalId, String storedSelectorParametersJson) {
  }

  public Optional<BundleJob> find(UUID externalId) {
    return jdbc.query(JOB_COLUMNS + " where external_id = :externalId",
        params("externalId", externalId), (rs, n) -> map(rs)).stream().findFirst();
  }

  /**
   * The key's job reflecting the newest state: the one still waiting if there is one, otherwise
   * the most recently claimed, otherwise the most recently submitted. A delayed re-run is not
   * waiting until it is due, so the bundle it follows stays the latest until then. created_at
   * is the submitting transaction's start time, so it alone cannot order jobs from overlapping
   * transactions.
   */
  Optional<BundleJob> findLatest(String coalesceKey) {
    return jdbc.query(JOB_COLUMNS + """
         where coalesce_key = :coalesceKey
         order by (state = 'QUEUED' and attempts = 0
                   and (next_attempt_at is null or next_attempt_at <= now())) desc,
                  claimed_at desc nulls last,
                  created_at desc, external_id
         limit 1
        """, params("coalesceKey", coalesceKey), (rs, n) -> map(rs)).stream().findFirst();
  }

  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts) {
    return jdbc.query("""
        with claimable as (
          select external_id from bundling.bundle_job
          where attempts < :maxAttempts
            and request_version <= :maxVersion
            and ((state = 'QUEUED' and (next_attempt_at is null or next_attempt_at <= now()))
                 or (state = :inProgress and lease_expires_at <= now()))
          order by priority desc, created_at, external_id
          limit :limit
          for update skip locked
        )
        update bundling.bundle_job job
        set state = :inProgress, attempts = job.attempts + 1, lease_owner = :leaseOwner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null, claimed_at = now(), updated_at = now()
        from claimable where job.external_id = claimable.external_id
        returning job.external_id, job.attempts, job.created_at, job.updated_at,
            job.coalesce_key, job.coalesced_submissions, job.claimed_at,
            job.request_version, job.request::text as request,
            job.selector_parameters::text as selector_parameters,
            job.execution_context::text as execution_context,
            job.transient_history::text as transient_history, job.priority
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", leaseDuration.toMillis(),
            "maxAttempts", maxAttempts, "inProgress", IN_PROGRESS,
            "maxVersion", BundleJobJson.REQUEST_VERSION),
        (rs, n) -> new ClaimedBundleJob(
            new BundleJob(rs.getObject("external_id", UUID.class), BundleJobState.IN_PROGRESS,
                rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"),
                Optional.ofNullable(rs.getString("coalesce_key")),
                rs.getInt("coalesced_submissions"), Optional.of(instant(rs, "claimed_at")),
                Optional.empty(), Optional.empty()),
            rs.getInt("request_version"), rs.getString("request"),
            rs.getString("selector_parameters"), rs.getString("execution_context"),
            rs.getString("transient_history"), leaseOwner, rs.getInt("priority")));
  }

  List<UUID> failExhaustedStaleJobs(int maxAttempts) {
    return jdbc.query("""
        update bundling.bundle_job
        set state = 'FAILED', failure_code = :code, failure_documents = '[]'::jsonb, result = null,
            failure_message = 'The job was claimed ' || attempts || ' time(s) without recording a '
                || 'result and each lease expired; the attempt bound (' || :maxAttempts || ') is '
                || 'exhausted. The render most likely crashed; see the service logs.',
            lease_owner = null, lease_expires_at = null, next_attempt_at = null, updated_at = now()
        where state = :inProgress and lease_expires_at <= now() and attempts >= :maxAttempts
        returning external_id
        """,
        params("maxAttempts", maxAttempts, "code", BundleErrorCode.ASSEMBLY_FAILED.name(),
            "inProgress", IN_PROGRESS),
        (rs, n) -> rs.getObject("external_id", UUID.class));
  }

  /**
   * Marks the job completed and records the bundle it rendered, in one statement: the bundle row
   * exists exactly when the job completed, and neither is written by a worker that lost its lease.
   */
  boolean markCompleted(UUID externalId, BundleJobState state, String resultJson,
      BundleRecord bundle, String leaseOwner) {
    return jdbc.update("""
        with completed as (
          update bundling.bundle_job
          set state = :state, result = :result::jsonb,
              failure_code = null, failure_message = null,
              failure_documents = null, lease_owner = null, lease_expires_at = null,
              next_attempt_at = null, updated_at = now()
          where external_id = :externalId and lease_owner = :leaseOwner and state = :inProgress
          returning external_id, coalesce_key, claimed_at, created_at, execution_context
        )
        insert into bundling.bundle (job_id, bundle_key, case_reference, selected_at, outcome,
          file_name, media_type, size_bytes, sha256, page_count, request, documents,
          missing_documents, warnings, document_id, document_url, binary_url)
        select external_id, coalesce_key, execution_context ->> 'caseReference',
          coalesce(claimed_at, created_at), :outcome, :fileName, :mediaType, :size, :sha256,
          :pageCount, :request::jsonb, :documents::jsonb, :missingDocuments::jsonb,
          :warnings::jsonb, :documentId, :documentUrl, :binaryUrl
        from completed
        """,
        params("externalId", externalId, "state", state.name(), "result", resultJson,
            "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS,
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

  Optional<BundleMetadata> findBundleForJob(UUID jobId) {
    return jdbc.query(BUNDLE_COLUMNS + " where job_id = :jobId", params("jobId", jobId),
        (rs, n) -> mapBundle(rs)).stream().findFirst();
  }

  /** The key's bundle whose documents were selected last. */
  Optional<BundleMetadata> findLatestBundle(String bundleKey) {
    return jdbc.query(BUNDLE_COLUMNS + """
         where bundle_key = :bundleKey
         order by selected_at desc, created_at desc, id
         limit 1
        """, params("bundleKey", bundleKey), (rs, n) -> mapBundle(rs)).stream().findFirst();
  }

  /**
   * Stored PDFs of bundles that a bundle selected later under the same key replaced at least
   * {@code replacedFor} ago.
   */
  List<UUID> findReplacedDocuments(Duration replacedFor, int limit) {
    return jdbc.query("""
        select document_id from bundling.bundle old
        where document_id is not null and deleted_at is null
          and exists (select 1 from bundling.bundle newer
                      where newer.bundle_key = old.bundle_key
                        and (newer.selected_at, newer.created_at) > (old.selected_at, old.created_at)
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

  boolean markFailed(UUID externalId, BundleErrorCode code, String message,
      String documentFailuresJson, String transientHistoryJson, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'FAILED', failure_code = :code, failure_message = :message,
            failure_documents = :documentFailures::jsonb,
            transient_history = coalesce(:transientHistory::jsonb, transient_history),
            result = null, lease_owner = null, lease_expires_at = null, next_attempt_at = null,
            updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = :inProgress
        """,
        params("externalId", externalId, "code", code.name(), "message", message,
            "documentFailures", documentFailuresJson, "transientHistory", transientHistoryJson,
            "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS)) == 1;
  }

  boolean requeueForRetry(UUID externalId, Instant nextAttemptAt, String transientHistoryJson,
      String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'QUEUED', next_attempt_at = :nextAttemptAt,
            transient_history = :transientHistory::jsonb,
            lease_owner = null, lease_expires_at = null, updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = :inProgress
        """,
        params("externalId", externalId,
            "nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
            "transientHistory", transientHistoryJson, "leaseOwner", leaseOwner,
            "inProgress", IN_PROGRESS)) == 1;
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
    return new BundleMetadata(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
        Optional.ofNullable(rs.getString("bundle_key")),
        Optional.ofNullable(rs.getString("case_reference")), instant(rs, "selected_at"),
        instant(rs, "created_at"),
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
        instant(rs, "created_at"), instant(rs, "updated_at"),
        Optional.ofNullable(rs.getString("coalesce_key")), rs.getInt("coalesced_submissions"),
        Optional.ofNullable(rs.getObject("claimed_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant),
        Optional.ofNullable(rs.getString("result")), failure);
  }
}
