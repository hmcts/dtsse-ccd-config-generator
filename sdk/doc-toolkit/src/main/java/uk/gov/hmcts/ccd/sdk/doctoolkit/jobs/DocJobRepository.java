package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.CaseDocStatus;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobState;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;

/**
 * JDBC access to {@code bundling.doc_job}. Queueing joins the caller's transaction. Every write a
 * worker makes is guarded by its lease and, for a kept-up-to-date document, by the revision it is
 * working on, so a worker that lost its lease, or whose request was superseded, changes nothing.
 */
public class DocJobRepository {
  private static final String COLUMNS = """
      select id, case_reference, job_key, revision, state, attempts, submitted_at, updated_at,
             result::text as result, failure_reason, failure_message,
             failure_documents::text as failure_documents
      from bundling.doc_job
      """;

  /** Whether a worker still holds the job, at the revision it claimed. */
  private static final String HELD = " " + """
      id = :id and lease_owner = :owner and revision is not distinct from cast(:revision as bigint)
      and state = 'IN_PROGRESS'
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public DocJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A job a worker has claimed. */
  public record Claimed(DocJob job, int requestVersion, String requestJson, String resultJson,
      int placeholderRetries, String leaseOwner) {
  }

  /** Queues a one-off job. */
  public void insertOneOff(UUID id, long caseReference, String requestJson) {
    jdbc.update("""
        insert into bundling.doc_job
          (id, case_reference, request_version, request, fingerprint, state)
        values (:id, :case, :version, cast(:request as jsonb),
          sha256(convert_to(cast(:request as jsonb)::text, 'UTF8')), 'QUEUED')
        """, params("id", id, "case", caseReference, "version", DocRequestJson.REQUEST_VERSION,
        "request", requestJson));
  }

  /**
   * Queues a kept-up-to-date document at {@code revision}, unless the row already has a newer
   * revision or the same request. A row a worker holds stays held: the newer request runs once
   * that worker lets go.
   *
   * @return whether anything was queued
   */
  public boolean queueKeyed(UUID id, long caseReference, String key, long revision,
      String requestJson) {
    return jdbc.update("""
        insert into bundling.doc_job
          (id, case_reference, job_key, revision, request_version, request, fingerprint, state)
        values (:id, :case, :key, :revision, :version, cast(:request as jsonb),
          sha256(convert_to(cast(:request as jsonb)::text, 'UTF8')), 'QUEUED')
        on conflict (case_reference, job_key) do update
          set revision = excluded.revision, request_version = excluded.request_version,
              request = excluded.request, fingerprint = excluded.fingerprint,
              attempts = 0, placeholder_retries = 0, next_attempt_at = null, result = null,
              failure_reason = null, failure_message = null, failure_documents = null,
              state = case when doc_job.state = 'IN_PROGRESS' then 'IN_PROGRESS' else 'QUEUED' end,
              updated_at = now()
          where doc_job.revision < excluded.revision
            and doc_job.fingerprint is distinct from excluded.fingerprint
        """, params("id", id, "case", caseReference, "key", key, "revision", revision,
        "version", DocRequestJson.REQUEST_VERSION, "request", requestJson)) == 1;
  }

  /**
   * Forgets a kept-up-to-date document the case no longer has. A worker still rendering it finds
   * the job gone, so its result is handed to nobody.
   *
   * @return whether the case had one
   */
  public boolean deleteKeyed(long caseReference, String key) {
    return jdbc.update("delete from bundling.doc_job where case_reference = :case and job_key = :key",
        params("case", caseReference, "key", key)) == 1;
  }

  /** A job, by id. */
  public Optional<DocJob> find(UUID id) {
    return jdbc.query(COLUMNS + " where id = :id", params("id", id), (rs, n) -> map(rs))
        .stream().findFirst();
  }

  /** Where a case's kept-up-to-date document is. */
  public Optional<CaseDocStatus> status(long caseReference, String key) {
    return jdbc.query(COLUMNS + " where case_reference = :case and job_key = :key",
        params("case", caseReference, "key", key), (rs, n) -> {
          DocJob job = map(rs);
          return new CaseDocStatus(job.state(), job.revision().orElse(0), job.attempts(),
              job.failure());
        }).stream().findFirst();
  }

  /**
   * Claims up to {@code limit} runnable jobs: queued and due, or held by a worker whose lease
   * expired. Attempts are counted here, so an attempt whose worker dies counts.
   */
  public List<Claimed> claim(int limit, String leaseOwner, Duration lease, int maxAttempts) {
    return jdbc.query("""
        with claimable as (
          select id from bundling.doc_job
          where request_version = :version
            and ((state = 'QUEUED' and (next_attempt_at is null or next_attempt_at <= now()))
                 or (state = 'IN_PROGRESS' and lease_expires_at <= now()
                     and attempts < :maxAttempts))
          order by coalesce(next_attempt_at, submitted_at), id
          limit :limit
          for update skip locked
        )
        update bundling.doc_job job
        set state = 'IN_PROGRESS', attempts = job.attempts + 1, lease_owner = :owner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null, updated_at = now()
        from claimable where job.id = claimable.id
        returning job.id, job.case_reference, job.job_key, job.revision, job.state, job.attempts,
            job.submitted_at, job.updated_at, job.result::text as result, job.failure_reason,
            job.failure_message, job.failure_documents::text as failure_documents,
            job.request_version, job.request::text as request, job.placeholder_retries
        """,
        params("limit", limit, "owner", leaseOwner, "leaseMillis", lease.toMillis(),
            "maxAttempts", maxAttempts, "version", DocRequestJson.REQUEST_VERSION),
        (rs, n) -> new Claimed(map(rs), rs.getInt("request_version"), rs.getString("request"),
            rs.getString("result"), rs.getInt("placeholder_retries"), leaseOwner));
  }

  /** Fails jobs whose every attempt's worker died, returning them. */
  public List<DocJob> failCrashed(int maxAttempts) {
    return jdbc.query("""
        update bundling.doc_job
        set state = 'FAILED', failure_reason = 'CRASHED', failure_documents = '[]'::jsonb,
            failure_message = 'The job was claimed ' || attempts || ' time(s) and each worker'
                || ' stopped before finishing it. The render most likely ran out of memory;'
                || ' see the service logs.',
            lease_owner = null, lease_expires_at = null, next_attempt_at = null,
            updated_at = now()
        where state = 'IN_PROGRESS' and lease_expires_at <= now() and attempts >= :maxAttempts
        returning id, case_reference, job_key, revision, state, attempts, submitted_at,
            updated_at, result::text as result, failure_reason, failure_message,
            failure_documents::text as failure_documents
        """, params("maxAttempts", maxAttempts), (rs, n) -> map(rs));
  }

  /** Extends a held job's lease. */
  public boolean renewLease(UUID id, String owner, Duration lease) {
    return jdbc.update("""
        update bundling.doc_job
        set lease_expires_at = now() + (:leaseMillis * interval '1 millisecond')
        where id = :id and lease_owner = :owner and state = 'IN_PROGRESS'
        """, params("id", id, "owner", owner, "leaseMillis", lease.toMillis())) == 1;
  }

  /** Stores the uploaded document, so a failing handler is retried without rendering again. */
  public boolean recordResult(UUID id, String owner, OptionalLong revision, String resultJson) {
    return jdbc.update("update bundling.doc_job set result = cast(:result as jsonb),"
        + " updated_at = now() where " + HELD, held(id, owner, revision)
        .addValue("result", resultJson)) == 1;
  }

  /**
   * Finishes a kept-up-to-date job: done, or queued again at {@code requeueAt} to re-run a render
   * that used placeholders for documents only temporarily unavailable.
   */
  public boolean finishKeyed(UUID id, String owner, long revision, Instant requeueAt) {
    return jdbc.update("""
        update bundling.doc_job
        set state = case when cast(:requeueAt as timestamptz) is null then 'DONE' else 'QUEUED' end,
            next_attempt_at = cast(:requeueAt as timestamptz),
            placeholder_retries = placeholder_retries
                + case when cast(:requeueAt as timestamptz) is null then 0 else 1 end,
            attempts = 0, result = null, lease_owner = null, lease_expires_at = null,
            failure_reason = null, failure_message = null, failure_documents = null,
            updated_at = now()
        where """ + HELD, held(id, owner, OptionalLong.of(revision))
        .addValue("requeueAt", requeueAt == null ? null
            : OffsetDateTime.ofInstant(requeueAt, ZoneOffset.UTC))) == 1;
  }

  /** Deletes a finished one-off job. */
  public boolean deleteHeld(UUID id, String owner) {
    return jdbc.update("delete from bundling.doc_job where " + HELD,
        held(id, owner, OptionalLong.empty())) == 1;
  }

  /**
   * Lets go of a job whose request was superseded while it ran, so the newer request runs. Does
   * nothing if the worker no longer holds it.
   */
  public boolean releaseSuperseded(UUID id, String owner) {
    return jdbc.update("""
        update bundling.doc_job
        set state = 'QUEUED', next_attempt_at = null, lease_owner = null, lease_expires_at = null,
            updated_at = now()
        where id = :id and lease_owner = :owner and state = 'IN_PROGRESS'
        """, params("id", id, "owner", owner)) == 1;
  }

  /**
   * Puts a held job back in the queue to try again at {@code next}.
   */
  public boolean requeue(UUID id, String owner, OptionalLong revision, Instant next) {
    return jdbc.update("""
        update bundling.doc_job
        set state = 'QUEUED', next_attempt_at = :next, lease_owner = null,
            lease_expires_at = null, updated_at = now()
        where """ + HELD, held(id, owner, revision)
        .addValue("next", OffsetDateTime.ofInstant(next, ZoneOffset.UTC))) == 1;
  }

  /** Fails a held job. */
  public boolean markFailed(UUID id, String owner, OptionalLong revision, String reason,
      String message, String documentsJson) {
    return jdbc.update("""
        update bundling.doc_job
        set state = 'FAILED', failure_reason = :reason, failure_message = :message,
            failure_documents = cast(:documents as jsonb), result = null, lease_owner = null,
            lease_expires_at = null, next_attempt_at = null, updated_at = now()
        where """ + HELD, held(id, owner, revision).addValue("reason", reason)
        .addValue("message", message).addValue("documents", documentsJson)) == 1;
  }

  /** Deletes a failed one-off job once its handler has been told. */
  public void deleteFailedOneOff(UUID id) {
    jdbc.update("delete from bundling.doc_job where id = :id and job_key is null"
        + " and state = 'FAILED'", params("id", id));
  }

  private static MapSqlParameterSource held(UUID id, String owner, OptionalLong revision) {
    return params("id", id, "owner", owner,
        "revision", revision.isPresent() ? revision.getAsLong() : null);
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
    String reason = rs.getString("failure_reason");
    Optional<DocJob.Failure> failure = reason == null ? Optional.empty()
        : Optional.of(new DocJob.Failure(reason, rs.getString("failure_message"),
            DocRequestJson.readFailures(rs.getString("failure_documents"))));
    String result = rs.getString("result");
    Optional<UploadedDoc> uploaded = result == null ? Optional.empty()
        : Optional.of(DocRequestJson.readResult(result).uploaded());
    long revision = rs.getLong("revision");
    OptionalLong revisionValue = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(revision);
    return new DocJob(rs.getObject("id", UUID.class), rs.getLong("case_reference"),
        Optional.ofNullable(rs.getString("job_key")), revisionValue,
        DocJobState.valueOf(rs.getString("state")), rs.getInt("attempts"),
        instant(rs, "submitted_at"), instant(rs, "updated_at"), uploaded, failure);
  }
}
