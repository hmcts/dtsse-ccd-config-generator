package uk.gov.hmcts.ccd.sdk.jobs;

/**
 * What a {@link CaseJob} did to a case, counted on the run.
 */
public final class CaseJobOutcome {
  private static final CaseJobOutcome APPLIED = new CaseJobOutcome(true);
  private static final CaseJobOutcome SKIPPED = new CaseJobOutcome(false);

  private final boolean applied;

  private CaseJobOutcome(boolean applied) {
    this.applied = applied;
  }

  /** The job did its work to the case. */
  public static CaseJobOutcome applied() {
    return APPLIED;
  }

  /** The case needed nothing doing. */
  public static CaseJobOutcome skipped() {
    return SKIPPED;
  }

  boolean wasApplied() {
    return applied;
  }
}
