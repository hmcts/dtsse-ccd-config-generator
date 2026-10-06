package uk.gov.hmcts.ccd.sdk.bundling.job;

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
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;

/**
 * JDBC access to the bundling.bundle_job outbox table. Every method is a single statement, so the
 * insert joins the caller's transaction while worker writes are statement-atomic. Claiming uses
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
    return jdbc.queryForObject("""
        insert into bundling.bundle_job
          (external_id, coalesce_key, state, request_version, request, selector_parameters,
           execution_context)
        values (:externalId, :coalesceKey, 'QUEUED', :requestVersion, null, :parameters::jsonb,
          :context::jsonb)
        on conflict (coalesce_key)
          where coalesce_key is not null and state = 'QUEUED' and attempts = 0
        do update set coalesced_submissions = bundle_job.coalesced_submissions + 1,
          last_coalesced_at = clock_timestamp(), updated_at = now()
        returning external_id, selector_parameters::text as selector_parameters
        """,
        params("externalId", externalId, "coalesceKey", coalesceKey,
            "requestVersion", BundleJobJson.REQUEST_VERSION, "parameters", selectorParametersJson,
            "context", executionContextJson),
        (rs, n) -> new CoalescedInsert(rs.getObject("external_id", UUID.class),
            rs.getString("selector_parameters")));
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
   * the most recently claimed, otherwise the most recently submitted. created_at is the
   * submitting transaction's start time, so it alone cannot order jobs from overlapping
   * transactions.
   */
  Optional<BundleJob> findLatest(String coalesceKey) {
    return jdbc.query(JOB_COLUMNS + """
         where coalesce_key = :coalesceKey
         order by (state = 'QUEUED' and attempts = 0) desc, claimed_at desc nulls last,
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
          order by created_at, external_id
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
            job.transient_history::text as transient_history
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
            rs.getString("transient_history")));
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

  boolean markCompleted(UUID externalId, BundleJobState state, String resultJson,
      String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = :state, result = :result::jsonb, failure_code = null, failure_message = null,
            failure_documents = null, lease_owner = null, lease_expires_at = null,
            next_attempt_at = null, updated_at = now()
        where external_id = :externalId and lease_owner = :leaseOwner and state = :inProgress
        """,
        params("externalId", externalId, "state", state.name(), "result", resultJson,
            "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS)) == 1;
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
