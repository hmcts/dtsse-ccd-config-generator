package uk.gov.hmcts.ccd.sdk.jobs;

import java.util.Set;

/**
 * Work done once to every case of some case types, such as a backfill. A bean of this type runs in
 * an environment when {@code ccd.jobs.run.<name>} is set there (for example the environment
 * variable {@code CCD_JOBS_RUN_BACKFILL_CASE_BUNDLES}) to a run id. Each run id runs once: set a
 * new one to run the job again.
 */
public interface CaseJob {

  /**
   * Identifies the job in config and in the {@code job_utils} schema: lower case letters, digits
   * and hyphens.
   */
  String name();

  /** The case types whose cases the job is applied to. */
  Set<String> caseTypes();

  /**
   * Does the job to one case. It runs in its own transaction, which also records that the case is
   * done, so a case is done once even if an instance stops or another takes over the run.
   *
   * @return whether the job did anything to the case, which the run counts
   */
  CaseJobOutcome apply(CaseMetadata metadata);

  /**
   * Whether {@link #apply} runs with the case locked, as an event does, so an event cannot change
   * the case between what the job reads and what it writes. A job that only reads can return false.
   */
  default boolean lockCase() {
    return true;
  }
}
