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
   * Does the job to one case. Unless {@link #applyIn} says otherwise, it runs in its own
   * transaction, which also records that the case is done, so a case is done once even if an
   * instance stops or another takes over the run.
   *
   * @return whether the job did anything to the case, which the run counts
   */
  CaseJobOutcome apply(CaseMetadata metadata);

  /**
   * How {@link #apply} is run: by default in a transaction with the case locked.
   */
  default ApplyIn applyIn() {
    return ApplyIn.LOCKED_TRANSACTION;
  }

  /**
   * How {@link #apply} is run.
   */
  enum ApplyIn {
    /**
     * In a transaction with the case locked, as an event locks it, so an event cannot change the
     * case between what the job reads and what it writes. For a job that writes tables an event's
     * auditing does not cover, such as queuing a document.
     */
    LOCKED_TRANSACTION,
    /** In a transaction without locking the case, for a job that only reads. */
    TRANSACTION,
    /**
     * Outside a transaction, for a job that changes the case through system events, which lock
     * the case and record the change in its history themselves. The case is recorded as done once
     * {@code apply} returns, so it can be applied again if the instance stops in between: pass
     * {@link CaseMetadata#idempotencyKey()} to the system event so it runs once.
     */
    NO_TRANSACTION
  }
}
