package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An external event on one case, driven the way its frontend drives it: start the event to be sent
 * an {@code O}, then post an {@code I} back. Typed by the event's payloads, so a test never deals
 * in case data.
 */
public final class ExternalEvent<O, I> {

  /** What starting the event gave the frontend: its payload, or the errors that stopped it. */
  record Started<O>(O payload, List<String> errors) {
  }

  private final String eventId;
  private final Function<CcdEventTestSupport.Actor, Started<O>> starter;
  private final BiFunction<CcdEventTestSupport.Actor, I, ExternalOutcome> submitter;
  private final CcdEventTestSupport.Actor actor;

  ExternalEvent(String eventId,
                Function<CcdEventTestSupport.Actor, Started<O>> starter,
                BiFunction<CcdEventTestSupport.Actor, I, ExternalOutcome> submitter,
                CcdEventTestSupport.Actor actor) {
    this.eventId = eventId;
    this.starter = starter;
    this.submitter = submitter;
    this.actor = actor;
  }

  /** The same event, started and submitted as this actor. */
  public ExternalEvent<O, I> as(CcdEventTestSupport.Actor value) {
    return new ExternalEvent<>(eventId, starter, submitter, Objects.requireNonNull(value));
  }

  /** Starts the event and returns the payload its start handler sends the frontend. */
  public O start() {
    Started<O> started = starter.apply(actor);
    if (!started.errors().isEmpty()) {
      throw new AssertionError("Expected " + eventId + " to start, got errors " + started.errors());
    }
    return started.payload();
  }

  /** Starts the event, expecting its start handler to refuse, and returns why. */
  public List<String> startExpectingRejection() {
    Started<O> started = starter.apply(actor);
    if (started.errors().isEmpty()) {
      throw new AssertionError("Expected " + eventId + " to refuse to start, but it started");
    }
    return started.errors();
  }

  /**
   * Starts the event and posts this payload from the revision it was started at, as the frontend
   * does, whatever the outcome.
   */
  public ExternalOutcome submit(I payload) {
    return submitter.apply(actor, payload);
  }

  public ExternalOutcome submitExpectingSuccess(I payload) {
    ExternalOutcome outcome = submit(payload);
    if (!outcome.accepted()) {
      throw new AssertionError("Expected " + eventId + " to accept the payload, got " + outcome);
    }
    return outcome;
  }

  /** Expects the submit handler to reject the payload with errors. */
  public ExternalOutcome submitExpectingRejection(I payload) {
    ExternalOutcome outcome = submit(payload);
    if (outcome.errors().isEmpty()) {
      throw new AssertionError("Expected " + eventId + " to reject the payload, got " + outcome);
    }
    return outcome;
  }

  /** Expects the application to refuse the submission with this HTTP status. */
  public ExternalOutcome submitExpectingFailure(I payload, int status) {
    ExternalOutcome outcome = submit(payload);
    if (outcome.status() != status || !outcome.errors().isEmpty()) {
      throw new AssertionError("Expected " + eventId + " to fail with HTTP " + status + ", got " + outcome);
    }
    return outcome;
  }
}
