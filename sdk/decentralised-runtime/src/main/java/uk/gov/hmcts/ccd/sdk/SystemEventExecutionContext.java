package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import lombok.NonNull;
import uk.gov.hmcts.ccd.sdk.api.AfterCommit;

/**
 * Locked case context supplied when a new system event action is executed.
 */
public record SystemEventExecutionContext(
    long caseReference,
    @NonNull UUID idempotencyKey,
    @NonNull String caseTypeId,
    @NonNull String currentState,
    @NonNull AfterCommit afterCommit
) {

  /** Runs the action once the event has committed, before the executor returns. */
  public void afterCommit(Runnable action) {
    afterCommit.add(action);
  }
}
