package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;

/** The coarse state machine's fixed mappings. */
class BundleJobStateTransitionTest {

  @Test
  void aPlainSuccessCompletesTheJob() {
    assertThat(BundleJobWorker.completionState(BundleOutcome.COMPLETED))
        .isEqualTo(BundleJobState.COMPLETED);
  }

  @Test
  void aWarningCarryingSuccessCompletesWithWarningsSoItIsNeverPresentedAsPlainSuccess() {
    assertThat(BundleJobWorker.completionState(BundleOutcome.COMPLETED_WITH_WARNINGS))
        .isEqualTo(BundleJobState.COMPLETED_WITH_WARNINGS);
  }

  @Test
  void exactlyTheCompletedAndFailedStatesAreTerminal() {
    assertThat(BundleJobState.QUEUED.terminal()).isFalse();
    assertThat(BundleJobState.RESOLVING.terminal()).isFalse();
    assertThat(BundleJobState.CONVERTING.terminal()).isFalse();
    assertThat(BundleJobState.ASSEMBLING.terminal()).isFalse();
    assertThat(BundleJobState.STORING.terminal()).isFalse();
    assertThat(BundleJobState.COMPLETED.terminal()).isTrue();
    assertThat(BundleJobState.COMPLETED_WITH_WARNINGS.terminal()).isTrue();
    assertThat(BundleJobState.FAILED.terminal()).isTrue();
  }
}
