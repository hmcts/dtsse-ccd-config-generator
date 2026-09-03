package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;
import java.util.Optional;

/**
 * History metadata and optional state transition produced by a system event action.
 */
public record SystemEventResult<State extends Enum<State>>(
    String eventId,
    String eventName,
    String summary,
    Optional<State> state
) {

  public SystemEventResult {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventName, "eventName");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(state, "state");
  }
}
