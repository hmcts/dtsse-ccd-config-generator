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
      select external_id, state, attempts, created_at, updated_at, result::text as result,
             failure_code, failure_message, failure_documents::text as failure_documents
      from bundling.bundle_job where external_id = :externalId
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final BundleJobJson json = new BundleJobJson();

  public BundleJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

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

  public Optional<BundleJob> find(UUID externalId) {
    return jdbc.query(JOB_COLUMNS, params("externalId", externalId), (rs, n) -> map(rs))
        .stream().findFirst();
  }

  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts) {
    return jdbc.query("""
        with claimable as (
          select external_id from bundling.bundle_job
          where attempts < :maxAttempts
            and ((state = 'QUEUED' and (next_attempt_at is null or next_attempt_at <= now()))
                 or (state = :inProgress and lease_expires_at <= now()))
          order by created_at, external_id
          limit :limit
          for update skip locked
        )
        update bundling.bundle_job job
        set state = :inProgress, attempts = job.attempts + 1, lease_owner = :leaseOwner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null, updated_at = now()
        from claimable where job.external_id = claimable.external_id
        returning job.external_id, job.attempts, job.created_at, job.updated_at,
            job.request_version, job.request::text as request,
            job.selector_parameters::text as selector_parameters,
            job.execution_context::text as execution_context,
            job.transient_history::text as transient_history
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", leaseDuration.toMillis(),
            "maxAttempts", maxAttempts, "inProgress", IN_PROGRESS),
        (rs, n) -> new ClaimedBundleJob(
            new BundleJob(rs.getObject("external_id", UUID.class), BundleJobState.IN_PROGRESS,
                rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"),
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
        Optional.ofNullable(rs.getString("result")), failure);
  }
}
