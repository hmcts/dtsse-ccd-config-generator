package uk.gov.hmcts.ccd.sdk.jobs;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a case job's runs through its cases, in reference order, one transaction per case. A run is
 * held by one instance at a time, through a lease renewed with each case; a run whose instance has
 * gone is carried on from its last case by the next instance to try it once the lease expires.
 */
public class CaseJobRunner {
  private static final Logger log = LoggerFactory.getLogger(CaseJobRunner.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Duration lease;
  private final int batchSize;
  private volatile boolean stopping;

  public CaseJobRunner(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Duration lease,
      int batchSize) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.lease = lease;
    this.batchSize = batchSize;
  }

  /**
   * Carries the run on, unless another instance holds it or it has finished or failed. Returns when
   * it finishes, fails, is taken over, or {@link #stop} is called.
   */
  public void run(CaseJob job, String runId) {
    jdbc.update("""
        insert into job_utils.case_job_run (job_name, run_id) values (:job, :run)
        on conflict do nothing
        """, Map.of("job", job.name(), "run", runId));
    List<Held> claimed = jdbc.query("""
        update job_utils.case_job_run
           set lease_token = gen_random_uuid(), lease_until = now() + :lease * interval '1 second'
         where job_name = :job and run_id = :run and finished_at is null and failed_at is null
           and (lease_until is null or lease_until < now())
        returning lease_token, last_reference
        """, Map.of("job", job.name(), "run", runId, "lease", lease.toSeconds()),
        (rs, row) -> new Held(job, runId, rs.getObject("lease_token", UUID.class),
            rs.getLong("last_reference")));
    if (!claimed.isEmpty()) {
      carryOn(claimed.getFirst());
    }
  }

  /** Makes runs in progress give up their lease after the case they are on. */
  public void stop() {
    stopping = true;
  }

  private void carryOn(Held held) {
    log.info("Running {} run {} from case {}", held.job.name(), held.runId, held.after);
    long after = held.after;
    while (!stopping) {
      List<Long> cases = jdbc.queryForList("""
          select reference from ccd.case_data
           where case_type_id in (:types) and reference > :after
           order by reference limit :limit
          """, Map.of("types", held.job.caseTypes(), "after", after, "limit", batchSize),
          Long.class);
      if (cases.isEmpty()) {
        end(held, "finished_at = now()", Map.of());
        log.info("Finished {} run {}: {}", held.job.name(), held.runId, jdbc.queryForObject(
            "select applied || ' cases applied, ' || skipped || ' skipped' from job_utils.case_job_run"
                + " where job_name = :job and run_id = :run",
            Map.of("job", held.job.name(), "run", held.runId), String.class));
        return;
      }
      for (long reference : cases) {
        if (stopping) {
          break;
        }
        try {
          tx.executeWithoutResult(status -> apply(held, reference));
        } catch (NotHeld e) {
          log.warn("{} run {} was taken over by another instance", held.job.name(), held.runId);
          return;
        } catch (RuntimeException e) {
          log.error("{} run {} failed at case {}; it stops here, and a new run id runs the job"
              + " again", held.job.name(), held.runId, reference, e);
          end(held, "failed_at = now(), failed_reference = :case, failure = :failure",
              Map.of("case", reference, "failure", e.toString()));
          return;
        }
        after = reference;
      }
    }
    end(held, "lease_until = null", Map.of());
  }

  private void apply(Held held, long reference) {
    List<CaseMetadata> found = jdbc.query("""
        select reference, case_type_id, state, case_revision from ccd.case_data
         where reference = :case
        """ + (held.job.lockCase() ? " for update" : ""), Map.of("case", reference),
        (rs, row) -> new CaseMetadata(rs.getLong("reference"), rs.getString("case_type_id"),
            rs.getString("state"), rs.getLong("case_revision")));
    // A case deleted since its batch was read is passed over.
    boolean applied = !found.isEmpty() && held.job.apply(found.getFirst()).wasApplied();
    int recorded = jdbc.update("""
        update job_utils.case_job_run
           set last_reference = :case, lease_until = now() + :lease * interval '1 second',
               applied = applied + :applied, skipped = skipped + 1 - :applied
         where job_name = :job and run_id = :run and lease_token = :token
        """, Map.of("case", reference, "lease", lease.toSeconds(), "job", held.job.name(),
        "run", held.runId, "token", held.token, "applied", applied ? 1 : 0));
    if (recorded != 1) {
      throw new NotHeld();
    }
  }

  private void end(Held held, String set, Map<String, Object> values) {
    var params = new HashMap<String, Object>(values);
    params.putAll(Map.of("job", held.job.name(), "run", held.runId, "token", held.token));
    jdbc.update("update job_utils.case_job_run set lease_token = null, " + set
        + " where job_name = :job and run_id = :run and lease_token = :token", params);
  }

  private record Held(CaseJob job, String runId, UUID token, long after) {
  }

  /** The run's lease has passed to another instance, so this case's work is rolled back. */
  private static final class NotHeld extends RuntimeException {
    NotHeld() {
      super(null, null, false, false);
    }
  }
}
