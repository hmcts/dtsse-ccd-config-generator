package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.Objects;

/**
 * An external event's contract: its id, the type the frontend is sent when it starts the event,
 * and the type it submits. Declare it once and use it both to configure the event and in tests.
 */
public record ExternalEventId<O, I>(String id, Class<O> startType, Class<I> submitType) {

  public ExternalEventId {
    Objects.requireNonNull(id);
    Objects.requireNonNull(submitType);
  }

  public static <O, I> ExternalEventId<O, I> of(String id, Class<O> startType, Class<I> submitType) {
    return new ExternalEventId<>(id, Objects.requireNonNull(startType), submitType);
  }

  /** An external event that sends its frontend nothing when it starts. */
  public static <I> ExternalEventId<Void, I> of(String id, Class<I> submitType) {
    return new ExternalEventId<>(id, null, submitType);
  }
}
