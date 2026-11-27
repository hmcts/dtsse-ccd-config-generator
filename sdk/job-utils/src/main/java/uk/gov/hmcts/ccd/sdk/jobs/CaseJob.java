package uk.gov.hmcts.ccd.sdk.jobs;

import java.util.Set;

/**
 * Work done once to every case of some case types, such as a backfill or a data fix. A bean of
 * this type runs in an environment when {@code ccd.jobs.run.<name>} is set there (for example the
 * environment variable {@code CCD_JOBS_RUN_BACKFILL_CASE_BUNDLES}) to a run id. Each run id runs
 * once: set a new one to run the job again.
 *
 * <p>Each case is done in a system event of its own, named after the job, so the job's changes are
 * audited and the case is locked while it reads and writes, as in any event. The application needs
 * a system user ({@code ccd.decentralised-runtime.system-user.id}) for a job to run.
 */
public interface CaseJob {

  /**
   * Identifies the job in config, in the {@code job_utils} schema, and as its system event's id
   * and name: up to 30 lower case letters, digits and hyphens.
   */
  String name();

  /** The case types whose cases the job is applied to. */
  Set<String> caseTypes();

  /**
   * Does the job to one case, inside its system event with the case locked: read the case here,
   * not before, so what the job decides on is current.
   *
   * @return {@link CaseJobOutcome#applied()} to keep the event and what the job wrote, or
   *     {@link CaseJobOutcome#skipped()} to leave the case as it was, with no event
   */
  CaseJobOutcome apply(CaseMetadata metadata);
}
