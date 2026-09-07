package uk.gov.hmcts.ccd.sdk;

import java.util.Optional;
import lombok.NonNull;

/**
 * History metadata and optional state transition produced by a system event action.
 */
public record SystemEventResult(
    @NonNull String eventId,
    @NonNull String eventName,
    @NonNull Optional<String> summary,
    @NonNull Optional<? extends Enum<?>> state
) {

  public static SystemEventResult withoutStateTransition(String eventId, String eventName) {
    return new SystemEventResult(eventId, eventName, Optional.empty(), Optional.empty());
  }

  public static SystemEventResult withoutStateTransition(
      String eventId,
      String eventName,
      String summary
  ) {
    return new SystemEventResult(eventId, eventName, Optional.of(summary), Optional.empty());
  }

  public static SystemEventResult withStateTransition(
      String eventId,
      String eventName,
      Enum<?> state
  ) {
    return new SystemEventResult(eventId, eventName, Optional.empty(), Optional.of(state));
  }

  public static SystemEventResult withStateTransition(
      String eventId,
      String eventName,
      String summary,
      Enum<?> state
  ) {
    return new SystemEventResult(eventId, eventName, Optional.of(summary), Optional.of(state));
  }
}
