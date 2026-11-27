package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;

/**
 * JDBC access to {@code bundling.doc_job}, the work not yet done, and {@code bundling.doc_rendered},
 * what was last handed to the service. Submitting joins the case event's transaction. Every write a
 * worker makes is guarded by the case revision and request it rendered and its lease token, so a
 * worker whose request was superseded, or whose lease ran out, changes nothing.
 */
public class DocJobRepository {

  /**
   * The job a worker holds, at the revision and request it rendered. A request replaced at the same
   * revision keeps the lease, so the fingerprint is what tells the worker it was superseded.
   */
  private static final String HELD = " " + """
      case_reference = :case and job_key = :key and case_revision = :revision
        and fingerprint = :fingerprint and lease_token = :token
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public DocJobRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A job a worker has claimed: the request it renders, the attempt it is on and its lease. */
  public record Claimed(DocJob job, UUID fingerprint, int attempts, String requestJson,
      UUID leaseToken) {
  }

  /**
   * Submits a case's document at the case's revision in this transaction. A request the same as
   * the one last handed to the service cancels any pending work; one the same as the pending
   * request leaves it, and its revision, as they are; anything else replaces the pending request.
   * A row a worker holds stays held: the worker's result is refused, and the newer request runs
   * once it lets go.
   *
   * @return whether a render is now queued for it
   */
  public boolean submit(long caseReference, String key, String requestJson) {
    MapSqlParameterSource params = params("case", caseReference, "key", key,
        "request", requestJson);
    boolean delivered = !jdbc.queryForList("""
        select 1 from bundling.doc_rendered
        where case_reference = :case and job_key = :key
          and fingerprint = md5(cast(:request as jsonb)::text)::uuid
        """, params, Integer.class).isEmpty();
    if (delivered) {
      jdbc.update("delete from bundling.doc_job where case_reference = :case and job_key = :key",
          params);
      return false;
    }
    return jdbc.update("""
        insert into bundling.doc_job (case_reference, job_key, case_revision, fingerprint, request)
        values (:case, :key, (select case_revision from ccd.case_data where reference = :case),
          md5(cast(:request as jsonb)::text)::uuid, cast(:request as jsonb))
        on conflict (case_reference, job_key) do update
          set case_revision = excluded.case_revision, fingerprint = excluded.fingerprint,
              request = excluded.request, attempts = 0, next_attempt_at = null,
              submitted_at = now()
          where doc_job.fingerprint <> excluded.fingerprint
        """, params) == 1;
  }

  /**
   * Claims up to {@code limit} runnable jobs, due and not held by a live lease, each under a new
   * lease token. Attempts are counted here, so an attempt whose worker dies counts.
   */
  public List<Claimed> claim(int limit, Duration lease, int maxAttempts) {
    return jdbc.query("""
        with claimable as (
          select case_reference, job_key from bundling.doc_job
          where (lease_expires_at is null or lease_expires_at <= now())
            and (next_attempt_at is null or next_attempt_at <= now())
            and attempts < :maxAttempts
          order by coalesce(next_attempt_at, submitted_at)
          limit :limit
          for update skip locked
        )
        update bundling.doc_job job
        set attempts = job.attempts + 1, lease_token = gen_random_uuid(),
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null
        from claimable
        where job.case_reference = claimable.case_reference and job.job_key = claimable.job_key
        returning job.case_reference, job.job_key, job.case_revision, job.fingerprint,
            job.attempts, job.lease_token, job.request::text as request
        """, params("limit", limit, "leaseMillis", lease.toMillis(), "maxAttempts", maxAttempts),
        (rs, n) -> claimed(rs, rs.getString("request")));
  }

  /**
   * Jobs whose every attempt's worker died: at their last attempt, under a lease that expired.
   * Each is returned as held by that dead worker, so giving it up is guarded as usual.
   */
  public List<Claimed> crashed(int maxAttempts) {
    return jdbc.query("""
        select case_reference, job_key, case_revision, fingerprint, attempts, lease_token
        from bundling.doc_job
        where lease_expires_at <= now() and attempts >= :maxAttempts
        """, params("maxAttempts", maxAttempts), (rs, n) -> claimed(rs, null));
  }

  /**
   * Locks the case's row until the transaction ends: the lock every case event takes first, so a
   * transaction taking it before anything else is ordered with case events and cannot deadlock
   * with them.
   */
  public void lockCase(long caseReference) {
    jdbc.queryForList("select reference from ccd.case_data where reference = :case for update",
        params("case", caseReference));
  }

  /**
   * Takes a held job off the queue; false if the worker no longer holds it at the revision and
   * request it rendered.
   */
  public boolean dequeue(Claimed claimed) {
    return jdbc.update("delete from bundling.doc_job where" + HELD, held(claimed)) == 1;
  }

  /** Records the claimed request as the one last handed to the service. */
  public void rendered(Claimed claimed) {
    jdbc.update("""
        insert into bundling.doc_rendered (case_reference, job_key, fingerprint)
        values (:case, :key, :fingerprint)
        on conflict (case_reference, job_key) do update set fingerprint = excluded.fingerprint
        """, held(claimed));
  }

  /**
   * Puts a held job back to try again at {@code next}.
   */
  public boolean retryAt(Claimed claimed, Instant next) {
    return jdbc.update("""
        update bundling.doc_job
        set lease_token = null, lease_expires_at = null, next_attempt_at = :next
        where""" + HELD, held(claimed)
        .addValue("next", OffsetDateTime.ofInstant(next, ZoneOffset.UTC))) == 1;
  }

  /**
   * Lets go of a job whose request was superseded while it ran, so the newer request runs at once.
   * Does nothing if the worker's lease is no longer on it.
   */
  public void release(Claimed claimed) {
    jdbc.update("""
        update bundling.doc_job set lease_token = null, lease_expires_at = null
        where case_reference = :case and job_key = :key and lease_token = :token
        """, held(claimed));
  }

  private static Claimed claimed(ResultSet rs, String requestJson) throws SQLException {
    return new Claimed(new DocJob(rs.getLong("case_reference"), rs.getString("job_key"),
        rs.getLong("case_revision")), rs.getObject("fingerprint", UUID.class),
        rs.getInt("attempts"), requestJson, rs.getObject("lease_token", UUID.class));
  }

  private static MapSqlParameterSource held(Claimed claimed) {
    return params("case", claimed.job().caseReference(), "key", claimed.job().key(),
        "revision", claimed.job().caseRevision(), "fingerprint", claimed.fingerprint(),
        "token", claimed.leaseToken());
  }

  private static MapSqlParameterSource params(Object... keysAndValues) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.addValue((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return params;
  }
}
