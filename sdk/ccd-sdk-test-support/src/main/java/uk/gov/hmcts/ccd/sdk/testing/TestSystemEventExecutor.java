package uk.gov.hmcts.ccd.sdk.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.ActorAttribution;
import uk.gov.hmcts.ccd.sdk.SystemEventAction;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionContext;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionResult;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;

/**
 * A {@link SystemEventExecutor} for unit tests that runs each action immediately against a case in
 * the given state and records the results.
 *
 * <p>It rejects a null result as the runtime does, and a result's state transition becomes the
 * state later actions see. It does not lock, persist, write history or replay idempotency keys:
 * integration tests use the real executor.
 */
public final class TestSystemEventExecutor implements SystemEventExecutor {

  public static final String CASE_TYPE_ID = "TestCaseType";

  private final List<SystemEventResult> results = new ArrayList<>();
  private String currentState;

  public TestSystemEventExecutor(String currentState) {
    this.currentState = currentState;
  }

  @Override
  public SystemEventExecutionResult execute(long caseReference, UUID idempotencyKey,
                                            SystemEventAction action) {
    SystemEventResult result = action.execute(
        new SystemEventExecutionContext(caseReference, idempotencyKey, CASE_TYPE_ID, currentState));
    if (result == null) {
      throw new IllegalArgumentException("System event action must return a result");
    }
    result.state().ifPresent(state -> currentState = String.valueOf(state));
    results.add(result);
    return new SystemEventExecutionResult(results.size(), SystemEventExecutionResult.Outcome.EXECUTED);
  }

  @Override
  public SystemEventExecutionResult execute(long caseReference, ActorAttribution actor,
                                            UUID idempotencyKey, SystemEventAction action) {
    return execute(caseReference, idempotencyKey, action);
  }

  /** The results of the actions executed so far, in order. */
  public List<SystemEventResult> results() {
    return List.copyOf(results);
  }
}
