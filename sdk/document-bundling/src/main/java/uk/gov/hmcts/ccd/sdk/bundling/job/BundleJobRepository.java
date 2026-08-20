package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;

/**
 * JDBC access to the {@code ccd_bundle_job} outbox table.
 *
 * <p>Every method issues a single SQL statement through the caller's {@code JdbcTemplate}, so
 * {@link #insertIfAbsent} joins whatever transaction is active in the caller — the transactional
 * half of the outbox — while worker-side updates run statement-atomically without needing a
 * surrounding transaction. Claiming uses {@code FOR UPDATE SKIP LOCKED} plus a lease, following
 * {@code sdk/task-management}.
 *
 * <p>Worker-side writes are lease-guarded: completion, failure, requeue, and release update the
 * row only while the caller still owns the lease and the job is still in progress. A worker that
 * lost its lease to a reclaimer gets a {@code false} return and must not treat its result as
 * recorded — the lease holder's state always stands, and a terminal row is never dragged back
 * into execution.
 */
public class BundleJobRepository {

  /** The version of the worker/adapter code writing rows, stored for diagnostics. */
  static final String ADAPTER_VERSION = "document-bundling/1";

  private static final List<String> IN_PROGRESS_STATES = List.of(
      BundleJobState.RESOLVING.name(),
      BundleJobState.CONVERTING.name(),
      BundleJobState.ASSEMBLING.name(),
      BundleJobState.STORING.name());

  private static final String JOB_COLUMNS = """
      select external_id, state, attempts, created_at, updated_at,
             result::text as result, failure_code, failure_message,
             failure_documents::text as failure_documents
      from ccd_bundle_job
      where external_id = ?
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final BundleJobJson json = new BundleJobJson();

  /**
   * Creates the repository.
   *
   * @param jdbc the consumer's JDBC template, bound to the database holding the outbox table
   */
  public BundleJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a queued job row in the caller's current transaction, or does nothing if a row with
   * the external id already exists — the idempotent half of "a repeated submission returns the
   * existing job".
   *
   * @param externalId the consumer-minted idempotency key
   * @param requestJson the submitted request JSON; null for a selector-parameters submission
   * @param selectorParametersJson the selector parameters JSON
   * @param executionContextJson the non-secret execution context JSON
   * @return true if a new row was inserted, false if the job already existed
   */
  boolean insertIfAbsent(UUID externalId, String requestJson, String selectorParametersJson,
      String executionContextJson) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("externalId", externalId)
        .addValue("requestVersion", BundleJobJson.REQUEST_VERSION)
        .addValue("adapterVersion", ADAPTER_VERSION)
        .addValue("request", requestJson)
        .addValue("selectorParameters", selectorParametersJson)
        .addValue("executionContext", executionContextJson);
    int inserted = jdbc.update(
        """
            insert into ccd_bundle_job
              (external_id, state, request_version, adapter_version, request,
               selector_parameters, execution_context)
            values
              (:externalId, 'QUEUED', :requestVersion, :adapterVersion, :request::jsonb,
               :selectorParameters::jsonb, :executionContext::jsonb)
            on conflict (external_id) do nothing
            """,
        params);
    return inserted == 1;
  }

  /**
   * Looks up one job by its external id.
   *
   * @param externalId the consumer-minted idempotency key
   * @return the job, or empty if none was ever submitted with the id
   */
  public Optional<BundleJob> find(UUID externalId) {
    List<BundleJob> jobs = jdbc.getJdbcTemplate()
        .query(JOB_COLUMNS, jobMapper(), externalId);
    return jobs.stream().findFirst();
  }

  /**
   * Looks up one job on a fresh connection, outside any transaction bound to the calling
   * thread. Used when a concurrent duplicate submission aborted the caller's transaction under
   * {@code REPEATABLE READ}: the winner's committed row is visible here even though the caller's
   * own snapshot and connection are unusable.
   *
   * @param externalId the consumer-minted idempotency key
   * @return the job, or empty if no committed row exists yet
   */
  Optional<BundleJob> findOutsideCallerTransaction(UUID externalId) {
    DataSource dataSource = jdbc.getJdbcTemplate().getDataSource();
    if (dataSource == null) {
      return find(externalId);
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(JOB_COLUMNS)) {
      statement.setObject(1, externalId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to read bundle job " + externalId + " outside the caller's transaction", e);
    }
  }

  /**
   * The row's current lease owner, for lost-lease diagnostics.
   *
   * @param externalId the job's idempotency key
   * @return the current lease owner, or null when unleased or the row is missing
   */
  String leaseOwnerOf(UUID externalId) {
    List<String> owners = jdbc.query(
        "select lease_owner from ccd_bundle_job where external_id = :externalId",
        new MapSqlParameterSource("externalId", externalId),
        (rs, rowNum) -> rs.getString("lease_owner"));
    return owners.isEmpty() ? null : owners.get(0);
  }

  /**
   * Atomically claims up to {@code limit} executable jobs for this worker: queued jobs whose
   * next attempt is due, plus in-progress jobs whose lease has expired (stale-lease recovery) —
   * in both cases only while the attempt count is below the retry bound, so a job whose renders
   * keep crashing without recording anything is not reclaimed forever. Contending workers skip
   * each other's locked rows rather than blocking or double-claiming, and every claim increments
   * the attempt count and takes a fresh lease.
   *
   * @param limit the maximum number of jobs to claim
   * @param leaseOwner the claiming worker's identifier
   * @param leaseDuration how long the claim's lease lasts before the job is reclaimable
   * @param maxAttempts the retry policy's attempt bound; rows at or past it are never claimed
   * @return the claimed jobs, oldest first; empty when nothing is executable
   */
  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("limit", limit)
        .addValue("leaseOwner", leaseOwner)
        .addValue("leaseMillis", leaseDuration.toMillis())
        .addValue("maxAttempts", maxAttempts)
        .addValue("queued", BundleJobState.QUEUED.name())
        .addValue("resolving", BundleJobState.RESOLVING.name())
        .addValue("inProgressStates", IN_PROGRESS_STATES);
    return jdbc.query(
        """
            with claimable as (
              select external_id
              from ccd_bundle_job
              where attempts < :maxAttempts
                and ((state = :queued and (next_attempt_at is null or next_attempt_at <= now()))
                     or (state in (:inProgressStates) and lease_expires_at <= now()))
              order by created_at, external_id
              limit :limit
              for update skip locked
            )
            update ccd_bundle_job job
            set state = :resolving,
                attempts = job.attempts + 1,
                lease_owner = :leaseOwner,
                lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
                next_attempt_at = null,
                updated_at = now()
            from claimable
            where job.external_id = claimable.external_id
            returning job.external_id, job.attempts, job.request_version,
                job.request::text as request,
                job.selector_parameters::text as selector_parameters,
                job.execution_context::text as execution_context,
                job.transient_history::text as transient_history
            """,
        params,
        (rs, rowNum) -> new ClaimedBundleJob(
            rs.getObject("external_id", UUID.class),
            rs.getInt("attempts"),
            rs.getInt("request_version"),
            rs.getString("request"),
            rs.getString("selector_parameters"),
            rs.getString("execution_context"),
            rs.getString("transient_history")));
  }

  /**
   * Terminally fails in-progress jobs whose lease has expired and whose attempt count has
   * reached the bound — jobs whose renders crashed or were killed without ever recording a
   * result, which the claim predicate no longer picks up.
   *
   * @param maxAttempts the retry policy's attempt bound
   * @return the external ids of the jobs failed by this sweep
   */
  List<UUID> failExhaustedStaleJobs(int maxAttempts) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("maxAttempts", maxAttempts)
        .addValue("code", BundleErrorCode.ASSEMBLY_FAILED.name())
        .addValue("inProgressStates", IN_PROGRESS_STATES);
    return jdbc.query(
        """
            update ccd_bundle_job
            set state = 'FAILED',
                failure_code = :code,
                failure_message = 'The job was claimed ' || attempts || ' time(s) without ever '
                    || 'recording a result and each lease expired; the attempt bound ('
                    || :maxAttempts || ') is exhausted. The render most likely crashed or was '
                    || 'terminated. Nothing was published; see the service logs around each '
                    || 'lease expiry.',
                failure_documents = '[]'::jsonb,
                result = null,
                lease_owner = null, lease_expires_at = null, next_attempt_at = null,
                updated_at = now()
            where state in (:inProgressStates)
              and lease_expires_at <= now()
              and attempts >= :maxAttempts
            returning external_id
            """,
        params,
        (rs, rowNum) -> rs.getObject("external_id", UUID.class));
  }

  /**
   * Records a successful render: stores the published bundle JSON, moves the job to its
   * terminal completed state, clears any failure remnants and transient history, and releases
   * the lease. The write applies only while the caller still owns the lease of an in-progress
   * job; a stale worker whose job was reclaimed gets {@code false} and must not consider its
   * publish recorded.
   *
   * @param externalId the job's idempotency key
   * @param state the terminal state, completed or completed-with-warnings
   * @param resultJson the published {@code CcdBundle} as JSON
   * @param leaseOwner the writing worker's identifier, matched against the row's lease
   * @return true if the completion was recorded, false if the caller no longer owns the job
   */
  boolean markCompleted(UUID externalId, BundleJobState state, String resultJson,
      String leaseOwner) {
    int updated = jdbc.update(
        """
            update ccd_bundle_job
            set state = :state, result = :result::jsonb,
                failure_code = null, failure_message = null, failure_documents = null,
                transient_history = '[]'::jsonb,
                lease_owner = null, lease_expires_at = null, next_attempt_at = null,
                updated_at = now()
            where external_id = :externalId
              and lease_owner = :leaseOwner
              and state in (:inProgressStates)
            """,
        new MapSqlParameterSource()
            .addValue("externalId", externalId)
            .addValue("state", state.name())
            .addValue("result", resultJson)
            .addValue("leaseOwner", leaseOwner)
            .addValue("inProgressStates", IN_PROGRESS_STATES));
    return updated == 1;
  }

  /**
   * Records a terminal failure: stores the sanitised failure, moves the job to FAILED, clears
   * any stored result, and releases the lease. Nothing was published. The write applies only
   * while the caller still owns the lease of an in-progress job.
   *
   * @param externalId the job's idempotency key
   * @param code the stable error code
   * @param message the log-safe failure message
   * @param documentFailuresJson the responsible documents as JSON
   * @param transientHistoryJson the final transient history as JSON, or null to keep the
   *     existing history
   * @param leaseOwner the writing worker's identifier, matched against the row's lease
   * @return true if the failure was recorded, false if the caller no longer owns the job
   */
  boolean markFailed(UUID externalId, BundleErrorCode code, String message,
      String documentFailuresJson, String transientHistoryJson, String leaseOwner) {
    int updated = jdbc.update(
        """
            update ccd_bundle_job
            set state = 'FAILED', failure_code = :code, failure_message = :message,
                failure_documents = :documentFailures::jsonb,
                transient_history = coalesce(:transientHistory::jsonb, transient_history),
                result = null,
                lease_owner = null, lease_expires_at = null, next_attempt_at = null,
                updated_at = now()
            where external_id = :externalId
              and lease_owner = :leaseOwner
              and state in (:inProgressStates)
            """,
        new MapSqlParameterSource()
            .addValue("externalId", externalId)
            .addValue("code", code.name())
            .addValue("message", message)
            .addValue("documentFailures", documentFailuresJson)
            .addValue("transientHistory", transientHistoryJson)
            .addValue("leaseOwner", leaseOwner)
            .addValue("inProgressStates", IN_PROGRESS_STATES));
    return updated == 1;
  }

  /**
   * Returns a transiently failed job to the queue for a bounded retry: records the grown
   * transient history, sets when the next attempt becomes due, and releases the lease. The
   * write applies only while the caller still owns the lease of an in-progress job, so a stale
   * worker's late transient failure can never drag a completed, published bundle back into
   * execution.
   *
   * @param externalId the job's idempotency key
   * @param nextAttemptAt when the job becomes claimable again
   * @param transientHistoryJson the transient history including this attempt's failure
   * @param leaseOwner the writing worker's identifier, matched against the row's lease
   * @return true if the retry was recorded, false if the caller no longer owns the job
   */
  boolean requeueForRetry(UUID externalId, Instant nextAttemptAt, String transientHistoryJson,
      String leaseOwner) {
    int updated = jdbc.update(
        """
            update ccd_bundle_job
            set state = 'QUEUED', next_attempt_at = :nextAttemptAt,
                transient_history = :transientHistory::jsonb,
                lease_owner = null, lease_expires_at = null,
                updated_at = now()
            where external_id = :externalId
              and lease_owner = :leaseOwner
              and state in (:inProgressStates)
            """,
        new MapSqlParameterSource()
            .addValue("externalId", externalId)
            .addValue("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
            .addValue("transientHistory", transientHistoryJson)
            .addValue("leaseOwner", leaseOwner)
            .addValue("inProgressStates", IN_PROGRESS_STATES));
    return updated == 1;
  }

  /**
   * Releases a claim that was never executed — the dispatch was rejected before the render
   * started — returning the job to the queue immediately and handing back the attempt the claim
   * consumed. The write applies only while the caller still owns the lease.
   *
   * @param externalId the job's idempotency key
   * @param leaseOwner the releasing worker's identifier, matched against the row's lease
   * @return true if the claim was released, false if the caller no longer owns the job
   */
  boolean releaseClaim(UUID externalId, String leaseOwner) {
    int updated = jdbc.update(
        """
            update ccd_bundle_job
            set state = 'QUEUED', attempts = attempts - 1,
                lease_owner = null, lease_expires_at = null,
                updated_at = now()
            where external_id = :externalId
              and lease_owner = :leaseOwner
              and state in (:inProgressStates)
            """,
        new MapSqlParameterSource()
            .addValue("externalId", externalId)
            .addValue("leaseOwner", leaseOwner)
            .addValue("inProgressStates", IN_PROGRESS_STATES));
    return updated == 1;
  }

  private RowMapper<BundleJob> jobMapper() {
    return (rs, rowNum) -> map(rs);
  }

  private BundleJob map(ResultSet rs) throws SQLException {
    String resultJson = rs.getString("result");
    String failureCode = rs.getString("failure_code");
    Optional<CcdBundle> output = resultJson == null
        ? Optional.empty()
        : Optional.of(json.readBundle(resultJson));
    Optional<BundleJobFailure> failure = failureCode == null
        ? Optional.empty()
        : Optional.of(new BundleJobFailure(
            BundleErrorCode.valueOf(failureCode),
            rs.getString("failure_message"),
            readDocumentFailures(rs.getString("failure_documents"))));
    return new BundleJob(
        rs.getObject("external_id", UUID.class),
        BundleJobState.valueOf(rs.getString("state")),
        rs.getInt("attempts"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        output,
        failure);
  }

  private List<uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure> readDocumentFailures(
      String documentFailuresJson) {
    return documentFailuresJson == null
        ? List.of()
        : json.readDocumentFailures(documentFailuresJson);
  }
}
