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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * JDBC access to the bundling.bundle_job outbox table, and to bundling.bundle, which describes the
 * bundles completed jobs rendered. Every method is a single statement, so the
 * insert joins the caller's transaction while worker writes are statement-atomic. Claiming uses
 * FOR UPDATE SKIP LOCKED plus a lease; every worker-side write is guarded by the lease owner, so a
 * worker that lost its lease gets false back and the lease holder's state stands.
 */
public class BundleJobRepository {
  private static final String JOB_COLUMNS = """
      select external_id, state, attempts, created_at, updated_at, definition, case_reference,
             coalesced_submissions, claimed_at, failure_code,
             failure_message,
             failure_documents::text as failure_documents
      from bundling.bundle_job
      """;

  private static final String BUNDLE_COLUMNS = """
      select id, job_id, definition, case_reference, selected_at, created_at, outcome, file_name,
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

  /**
   * Inserts a job for the case, or joins the case's job that is still waiting for its first
   * claim, and returns that job's id. Joining updates that row, so the caller holds its lock until
   * it commits and the worker's SKIP LOCKED claim passes over it: a joined job is never claimed
   * before the change that joined it is visible. If the waiting job is being claimed concurrently,
   * Postgres waits for the claim, finds the row no longer waiting, and inserts a follow-up instead.
   *
   * <p>A newly inserted job is not claimable before notBefore (null for immediately) and carries
   * the count of re-runs for unavailable documents. Joining a delayed job with an immediate
   * submission makes it immediate, and the joined job keeps the higher of the two priorities, so
   * a live change promotes a waiting refresh job.
   */
  UUID insertOrJoin(UUID externalId, String definition, long caseReference, Instant notBefore,
      int unavailableRequeues, int priority) {
    return jdbc.queryForObject("""
        insert into bundling.bundle_job
          (external_id, definition, case_reference, next_attempt_at, unavailable_requeues, priority)
        values (:externalId, :definition, :caseReference, :notBefore, :requeues, :priority)
        on conflict (definition, case_reference) where state = 'QUEUED' and attempts = 0
        do update set coalesced_submissions = bundle_job.coalesced_submissions + 1,
          last_coalesced_at = clock_timestamp(), updated_at = clock_timestamp(),
          priority = greatest(bundle_job.priority, excluded.priority),
          next_attempt_at = case
            when bundle_job.next_attempt_at is null or excluded.next_attempt_at is null then null
            else least(bundle_job.next_attempt_at, excluded.next_attempt_at) end
        returning external_id
        """,
        params("externalId", externalId, "definition", definition, "caseReference", caseReference,
            "requeues", unavailableRequeues, "priority", priority,
            "notBefore", notBefore == null ? null : OffsetDateTime.ofInstant(notBefore, ZoneOffset.UTC)),
        UUID.class);
  }

  /** The case as CCD holds it: where a bundle of the case is stored. */
  SelectedCaseBundle selectedFor(long caseReference, BundleRequest request) {
    return jdbc.queryForObject("""
        select case_type_id, jurisdiction from ccd.case_data where reference = :caseReference
        """, params("caseReference", caseReference),
        (rs, n) -> new SelectedCaseBundle(caseReference, rs.getString("case_type_id"),
            rs.getString("jurisdiction"), request));
  }

  /**
   * Records that the key has refreshed the definition, and says whether that was new. A concurrent
   * caller waits its turn.
   */
  boolean recordRefresh(String key, String definition) {
    return jdbc.update("""
        insert into bundling.refresh_key (key, definition) values (:key, :definition)
        on conflict do nothing
        """, params("key", key, "definition", definition)) == 1;
  }

  /**
   * Queues a background render of the definition's bundle for every case, and returns how many it
   * queued. A case with a job already waiting is left to that job. Each case is locked before
   * its job is inserted, in reference order, so the refresh waits for an event that holds a case
   * instead of deadlocking with it when that event changes the case's bundle. Events on a case
   * the refresh has reached wait until it commits. An event that changes the bundle of a case
   * other than its own can still deadlock with a refresh.
   */
  int refresh(String definition) {
    return jdbc.update("""
        insert into bundling.bundle_job (definition, case_reference, priority)
        select :definition, reference, -1 from ccd.case_data order by reference for key share
        on conflict (definition, case_reference) where state = 'QUEUED' and attempts = 0
        do nothing
        """, params("definition", definition));
  }

  public Optional<BundleJob> find(UUID externalId) {
    return jdbc.query(JOB_COLUMNS + " where external_id = :externalId",
        params("externalId", externalId), (rs, n) -> map(rs)).stream().findFirst();
  }

  /**
   * The case's job reflecting the newest state: the one still waiting if there is one, otherwise
   * the most recently claimed, otherwise the most recently submitted. A delayed re-run is not
   * waiting until it is due, so the bundle it follows stays the latest until then. created_at
   * is the submitting transaction's start time, so it alone cannot order jobs from overlapping
   * transactions.
   */
  public Optional<BundleJob> findLatest(String definition, long caseReference) {
    return jdbc.query(JOB_COLUMNS + """
         where definition = :definition and case_reference = :caseReference
         order by (state = 'QUEUED' and attempts = 0
                   and (next_attempt_at is null or next_attempt_at <= now())) desc,
                  claimed_at desc nulls last,
                  created_at desc, external_id
         limit 1
        """, params("definition", definition, "caseReference", caseReference), (rs, n) -> map(rs)).stream()
        .findFirst();
  }

  /**
   * Claims the next jobs. A worker given definitions claims, and gives up on, only those, so a pod
   * on an older release leaves a definition it does not have to the pods that do.
   */
  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts, Collection<String> definitions) {
    if (definitions != null && definitions.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        with claimable as (
          select external_id from bundling.bundle_job
          where attempts < :maxAttempts
            and (:anyDefinition or definition in (:definitions))
            and ((state = 'QUEUED' and (next_attempt_at is null or next_attempt_at <= now()))
                 or (state = 'IN_PROGRESS' and lease_expires_at <= now()))
          order by priority desc, created_at, external_id
          limit :limit
          for update skip locked
        )
        update bundling.bundle_job job
        set state = 'IN_PROGRESS', attempts = job.attempts + 1, lease_owner = :leaseOwner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null, claimed_at = now(), updated_at = now()
        from claimable where job.external_id = claimable.external_id
        returning job.external_id, job.attempts, job.created_at, job.updated_at,
            job.definition, job.case_reference, job.coalesced_submissions, job.claimed_at,
            job.unavailable_requeues, job.priority, job.transient_history::text as transient_history
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", leaseDuration.toMillis(),
            "maxAttempts", maxAttempts, "anyDefinition", definitions == null,
            "definitions", definitions == null ? List.of("") : definitions),
        (rs, n) -> new ClaimedBundleJob(
            new BundleJob(rs.getObject("external_id", UUID.class), BundleJobState.IN_PROGRESS,
                rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("definition"), rs.getLong("case_reference"),
                rs.getInt("coalesced_submissions"), Optional.of(instant(rs, "claimed_at")),
                Optional.empty()),
            rs.getInt("unavailable_requeues"), rs.getInt("priority"),
            rs.getString("transient_history"), leaseOwner));
  }

  List<UUID> failExhaustedStaleJobs(int maxAttempts, Collection<String> definitions) {
    if (definitions != null && definitions.isEmpty()) {
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
          and (:anyDefinition or definition in (:definitions))
        returning external_id
        """,
        params("maxAttempts", maxAttempts, "code", BundleErrorCode.ASSEMBLY_FAILED.name(),
            "anyDefinition", definitions == null,
            "definitions", definitions == null ? List.of("") : definitions),
        (rs, n) -> rs.getObject("external_id", UUID.class));
  }

  /**
   * Whether a job of the same bundle has been claimed since this one was. Asked inside the
   * snapshot the job selects from: a later claim the snapshot can see will select newer documents,
   * and one it cannot see selects after this job, so the bundles of the jobs that do select are in
   * claim order. A job whose own lease was taken over counts too. Jobs claimed in one batch share
   * a claim time, so the job queued later counts as claimed later.
   */
  boolean superseded(BundleJob job) {
    return jdbc.queryForObject("""
        select exists (select 1 from bundling.bundle_job
          where definition = :definition and case_reference = :caseReference
            and (claimed_at, created_at, external_id) > (:claimedAt, :createdAt, :externalId))
        """, params("definition", job.definition(), "caseReference", job.caseReference(),
            "claimedAt", OffsetDateTime.ofInstant(job.claimedAt().orElseThrow(), ZoneOffset.UTC),
            "createdAt", OffsetDateTime.ofInstant(job.submittedAt(), ZoneOffset.UTC),
            "externalId", job.externalId()),
        Boolean.class);
  }

  /** Completes a superseded job without a bundle; the job claimed after it publishes one. */
  boolean markSuperseded(UUID externalId, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'COMPLETED', lease_owner = null, lease_expires_at = null, updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = 'IN_PROGRESS'
        """, params("externalId", externalId, "leaseOwner", leaseOwner)) == 1;
  }

  /**
   * Marks the job completed and records the bundle it rendered, in one statement: the bundle row
   * exists exactly when the job completed, and neither is written by a worker that lost its lease.
   */
  boolean markCompleted(UUID externalId, BundleJobState state, BundleRecord bundle,
      String leaseOwner) {
    return jdbc.update("""
        with completed as (
          update bundling.bundle_job
          set state = :state, failure_code = null, failure_message = null,
              failure_documents = null, lease_owner = null, lease_expires_at = null,
              next_attempt_at = null, updated_at = now()
          where external_id = :externalId and lease_owner = :leaseOwner and state = 'IN_PROGRESS'
          returning external_id, definition, case_reference, claimed_at, created_at
        )
        insert into bundling.bundle (job_id, definition, case_reference, selected_at, outcome,
          file_name, media_type, size_bytes, sha256, page_count, request, documents,
          missing_documents, warnings, document_id, document_url, binary_url)
        select external_id, definition, case_reference,
          coalesce(claimed_at, created_at), :outcome, :fileName, :mediaType, :size, :sha256,
          :pageCount, :request::jsonb, :documents::jsonb, :missingDocuments::jsonb,
          :warnings::jsonb, :documentId, :documentUrl, :binaryUrl
        from completed
        """,
        params("externalId", externalId, "state", state.name(),
            "leaseOwner", leaseOwner,
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

  public Optional<BundleMetadata> findBundleForJob(UUID jobId) {
    return jdbc.query(BUNDLE_COLUMNS + " where job_id = :jobId", params("jobId", jobId),
        (rs, n) -> mapBundle(rs)).stream().findFirst();
  }

  /** The case's bundle whose documents were selected last. */
  public Optional<BundleMetadata> findLatestBundle(String definition, long caseReference) {
    return jdbc.query(BUNDLE_COLUMNS + """
         where definition = :definition and case_reference = :caseReference
         order by selected_at desc, created_at desc, id
         limit 1
        """, params("definition", definition, "caseReference", caseReference), (rs, n) -> mapBundle(rs))
        .stream().findFirst();
  }

  /**
   * Stored PDFs of bundles that a bundle selected later for the same case replaced at least
   * {@code replacedFor} ago.
   */
  List<UUID> findReplacedDocuments(Duration replacedFor, int limit) {
    return jdbc.query("""
        select document_id from bundling.bundle old
        where document_id is not null and deleted_at is null
          and exists (select 1 from bundling.bundle newer
                      where newer.definition = old.definition and newer.case_reference = old.case_reference
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
            lease_owner = null, lease_expires_at = null, next_attempt_at = null,
            updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = 'IN_PROGRESS'
        """,
        params("externalId", externalId, "code", code.name(), "message", message,
            "documentFailures", documentFailuresJson, "transientHistory", transientHistoryJson,
            "leaseOwner", leaseOwner)) == 1;
  }

  boolean requeueForRetry(UUID externalId, Instant nextAttemptAt, String transientHistoryJson,
      String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'QUEUED', next_attempt_at = :nextAttemptAt,
            transient_history = :transientHistory::jsonb,
            lease_owner = null, lease_expires_at = null, updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = 'IN_PROGRESS'
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
    return new BundleMetadata(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
        rs.getString("definition"), rs.getLong("case_reference"), instant(rs, "selected_at"),
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
        rs.getString("definition"), rs.getLong("case_reference"), rs.getInt("coalesced_submissions"),
        Optional.ofNullable(rs.getObject("claimed_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant), failure);
  }
}
