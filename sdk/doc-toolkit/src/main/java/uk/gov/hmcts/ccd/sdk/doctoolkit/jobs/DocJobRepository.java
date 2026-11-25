package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

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
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobState;

/**
 * JDBC access to the {@code bundling.bundle_job} outbox table. Every method is one statement, so
 * an insert joins the caller's transaction and worker writes are atomic. Claiming uses
 * {@code FOR UPDATE SKIP LOCKED} and a lease; worker writes check the lease owner, so a worker
 * that lost its lease changes nothing.
 */
public class DocJobRepository {
  private static final String IN_PROGRESS = DocJobState.IN_PROGRESS.name();
  private static final String COLUMNS = """
      select external_id, state, attempts, case_id, event_id, created_at, updated_at,
             result::text as result, failure_code, failure_message,
             failure_documents::text as failure_documents
      from bundling.bundle_job
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public DocJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A job a worker holds the lease on. {@code resultJson} is set when only the handler is left.
   */
  public record Claimed(DocJob job, int requestVersion, String requestJson, String resultJson,
      String leaseOwner) {
  }

  public void insert(UUID id, String requestJson, long caseId, String eventId) {
    jdbc.update("""
        insert into bundling.bundle_job
          (external_id, state, request_version, request, case_id, event_id)
        values (:id, 'QUEUED', :version, :request::jsonb, :caseId, :eventId)
        """,
        params("id", id, "version", DocRequestJson.REQUEST_VERSION, "request", requestJson,
            "caseId", caseId, "eventId", eventId));
  }

  public Optional<DocJob> find(UUID id) {
    return jdbc.query(COLUMNS + " where external_id = :id", params("id", id),
        (rs, n) -> map(rs)).stream().findFirst();
  }

  /**
   * Claims up to {@code limit} runnable jobs: queued and due, or abandoned by a dead worker.
   */
  public List<Claimed> claim(int limit, String leaseOwner, Duration lease, int maxAttempts) {
    return jdbc.query("""
        with claimable as (
          select external_id from bundling.bundle_job
          where attempts < :maxAttempts and request_version = :version
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
        returning job.external_id, job.state, job.attempts, job.case_id, job.event_id,
            job.created_at, job.updated_at, job.result::text as result, job.failure_code,
            job.failure_message, job.failure_documents::text as failure_documents,
            job.request_version, job.request::text as request
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", lease.toMillis(),
            "maxAttempts", maxAttempts, "inProgress", IN_PROGRESS,
            "version", DocRequestJson.REQUEST_VERSION),
        (rs, n) -> new Claimed(map(rs), rs.getInt("request_version"), rs.getString("request"),
            rs.getString("result"), leaseOwner));
  }

  /** Fails jobs whose every attempt's worker died, returning them. */
  public List<DocJob> failCrashed(int maxAttempts) {
    return jdbc.query("""
        update bundling.bundle_job
        set state = 'FAILED', failure_code = 'CRASHED', failure_documents = '[]'::jsonb,
            failure_message = 'The job was claimed ' || attempts || ' time(s) and each worker'
                || ' stopped before finishing it. The render most likely ran out of memory;'
                || ' see the service logs.',
            lease_owner = null, lease_expires_at = null, next_attempt_at = null, updated_at = now()
        where state = :inProgress and lease_expires_at <= now() and attempts >= :maxAttempts
        returning external_id, state, attempts, case_id, event_id, created_at, updated_at,
            result::text as result, failure_code, failure_message,
            failure_documents::text as failure_documents
        """,
        params("maxAttempts", maxAttempts, "inProgress", IN_PROGRESS), (rs, n) -> map(rs));
  }

  /** Records the stored document, so a failing handler is retried without uploading again. */
  public boolean recordResult(UUID id, String resultJson, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job set result = :result::jsonb, updated_at = now()
        where external_id = :id and lease_owner = :leaseOwner and state = :inProgress
        """, params("id", id, "result", resultJson, "leaseOwner", leaseOwner,
        "inProgress", IN_PROGRESS)) == 1;
  }

  public boolean markCompleted(UUID id, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'COMPLETED', failure_code = null, failure_message = null,
            failure_documents = null, lease_owner = null, lease_expires_at = null,
            next_attempt_at = null, updated_at = now()
        where external_id = :id and lease_owner = :leaseOwner and state = :inProgress
        """, params("id", id, "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS)) == 1;
  }

  public boolean markFailed(UUID id, String reason, String message, String failuresJson,
      String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'FAILED', failure_code = :reason, failure_message = :message,
            failure_documents = :failures::jsonb, lease_owner = null, lease_expires_at = null,
            next_attempt_at = null, updated_at = now()
        where external_id = :id and lease_owner = :leaseOwner and state = :inProgress
        """, params("id", id, "reason", reason, "message", message, "failures", failuresJson,
        "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS)) == 1;
  }

  public boolean requeue(UUID id, Instant nextAttemptAt, String leaseOwner) {
    return jdbc.update("""
        update bundling.bundle_job
        set state = 'QUEUED', next_attempt_at = :next, lease_owner = null,
            lease_expires_at = null, updated_at = now()
        where external_id = :id and lease_owner = :leaseOwner and state = :inProgress
        """, params("id", id, "next", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
        "leaseOwner", leaseOwner, "inProgress", IN_PROGRESS)) == 1;
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

  private static DocJob map(ResultSet rs) throws SQLException {
    String reason = rs.getString("failure_code");
    String result = rs.getString("result");
    return new DocJob(rs.getObject("external_id", UUID.class),
        DocJobState.valueOf(rs.getString("state")), rs.getLong("case_id"),
        rs.getString("event_id"), rs.getInt("attempts"), instant(rs, "created_at"),
        instant(rs, "updated_at"),
        Optional.ofNullable(result).map(DocRequestJson::readResult),
        reason == null ? Optional.empty() : Optional.of(new DocJob.Failure(reason,
            rs.getString("failure_message"),
            DocRequestJson.readFailures(rs.getString("failure_documents")))));
  }
}
