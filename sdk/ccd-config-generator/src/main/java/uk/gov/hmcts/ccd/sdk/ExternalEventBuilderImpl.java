package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventBuilder;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventStates;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartHandler;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitHandler;

/** Registers an external event as a decentralised event whose handlers exchange typed payloads. */
class ExternalEventBuilderImpl<T, R extends HasRole, S, O, I>
    implements ExternalEventStates<T, R, S, O, I>, ExternalEventBuilder<T, R, S, O, I> {

  // Contradictory checks keep the event hidden in EXUI; CCD's API still starts and submits it.
  private static final String NEVER_SHOW = "[STATE]=\"NEVER_SHOW\" AND [STATE]!=\"NEVER_SHOW\"";

  private final ResolvedCCDConfig<T, S, R> config;
  private final Map<String, List<Event.EventBuilder<T, R, S>>> events;
  private final ExternalEventId<O, I> id;
  private final ExternalSubmitHandler<S, I> submitHandler;
  private Event.EventBuilder<T, R, S> event;

  ExternalEventBuilderImpl(ResolvedCCDConfig<T, S, R> config,
                           Map<String, List<Event.EventBuilder<T, R, S>>> events,
                           ExternalEventId<O, I> id,
                           ExternalSubmitHandler<S, I> submitHandler) {
    this.config = config;
    this.events = events;
    this.id = Objects.requireNonNull(id);
    this.submitHandler = Objects.requireNonNull(submitHandler, "An external event needs a submit handler");
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> forState(S state) {
    return register(Set.of(state));
  }

  @SafeVarargs
  @Override
  public final ExternalEventBuilder<T, R, S, O, I> forStates(S... states) {
    return register(Set.of(states));
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> forAllStates() {
    return register(config.allStates);
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> name(String name) {
    event.name(name);
    return this;
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> description(String description) {
    event.description(description);
    return this;
  }

  @SafeVarargs
  @Override
  public final ExternalEventBuilder<T, R, S, O, I> grant(Set<Permission> permissions, R... roles) {
    event.grant(permissions, roles);
    return this;
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> nonConcurrent() {
    event.nonConcurrent();
    return this;
  }

  @Override
  public ExternalEventBuilder<T, R, S, O, I> onStart(ExternalStartHandler<O> handler) {
    if (id.startType() == null) {
      throw new IllegalStateException("External event " + id.id() + " declares no start type");
    }
    event.externalStartHandler(Objects.requireNonNull(handler));
    return this;
  }

  private ExternalEventBuilder<T, R, S, O, I> register(Set<S> states) {
    if (event != null) {
      throw new IllegalStateException("External event " + id.id() + " already has its states");
    }
    // CCD reads an event without pre-states as one that creates a case; external events act on one.
    if (states.isEmpty()) {
      throw new IllegalArgumentException("External event " + id.id() + " needs at least one state");
    }
    event = Event.EventBuilder.builder(id.id(), config.caseClass, new PropertyUtils(), states, states);
    event.showCondition(NEVER_SHOW);
    event.external(id.submitType(), submitHandler);
    events.computeIfAbsent(id.id(), key -> Lists.newArrayList()).add(event);
    return this;
  }
}
