package uk.gov.hmcts.ccd.sdk.bundling.job;

/**
 * The states of a durable bundle job.
 */
public enum BundleJobState {

  /** Persisted in the outbox, waiting for a worker to claim it. */
  QUEUED,

  /** Resolving and spooling source documents. */
  RESOLVING,

  /** Converting sources to PDF. */
  CONVERTING,

  /** Assembling the bundle. */
  ASSEMBLING,

  /** Validating and publishing the output. */
  STORING,

  /** Published with no warnings. */
  COMPLETED,

  /** Published with non-fatal presentational warnings. */
  COMPLETED_WITH_WARNINGS,

  /** Failed; nothing was published, and the sanitised failure names the responsible documents. */
  FAILED;

  /**
   * Whether the state is terminal.
   *
   * @return true for the completed and failed states
   */
  public boolean terminal() {
    return this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED;
  }
}
