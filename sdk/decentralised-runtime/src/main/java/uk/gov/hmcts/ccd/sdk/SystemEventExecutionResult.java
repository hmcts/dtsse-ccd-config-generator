package uk.gov.hmcts.ccd.sdk;

import lombok.NonNull;

/**
 * Identifies the persisted event and whether this invocation executed the action or replayed it.
 */
public record SystemEventExecutionResult(long eventId, @NonNull Outcome outcome) {

  public enum Outcome {
    EXECUTED,
    REPLAYED
  }
}
