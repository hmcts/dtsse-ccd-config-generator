package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.ServiceEventBuilder;
import uk.gov.hmcts.ccd.sdk.api.ServiceEventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;

/**
 * EventTypeBuilder for service events using isolated DTO classes.
 * The DTO payload is serialised as JSON in a single opaque CCD field.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@RequiredArgsConstructor
public class ServiceEventTypeBuilderImpl<D, R extends HasRole, S> implements ServiceEventTypeBuilder<D, R, S> {

  private final ResolvedCCDConfig config;
  private final Map<String, List<Event.EventBuilder>> events;
  private final String id;
  private final Class<D> dtoClass;
  private final Submit<D, S> submitHandler;
  private final Start<D, S> startHandler;

  @Override
  public ServiceEventBuilder<D, R, S> forState(S state) {
    return build(Set.of(state), Set.of(state));
  }

  @Override
  public ServiceEventBuilder<D, R, S> initialState(S state) {
    return build(Set.of(), Set.of(state));
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStateTransition(S from, S to) {
    return build(Set.of(from), Set.of(to));
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStateTransition(EnumSet from, S to) {
    return build(from, Set.of(to));
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStateTransition(S from, EnumSet to) {
    return build(Set.of(from), to);
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStateTransition(EnumSet from, EnumSet to) {
    return build(from, to);
  }

  @Override
  public ServiceEventBuilder<D, R, S> forAllStates() {
    return build(config.getAllStates(), config.getAllStates());
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStates(EnumSet states) {
    return build(states, states);
  }

  @Override
  public ServiceEventBuilder<D, R, S> forStates(S... states) {
    return build(Set.of(states), Set.of(states));
  }

  protected ServiceEventBuilder<D, R, S> build(Set<S> preStates, Set<S> postStates) {
    Event.EventBuilder<D, R, S> result = Event.EventBuilder
        .builder(id, dtoClass, new PropertyUtils(), preStates, postStates, dtoClass);
    result.submitHandler(this.submitHandler);
    result.startHandler(this.startHandler);
    // Always-false show condition so XUI hides service events from the next steps dropdown.
    result.showCondition("[STATE]=\"NEVER_SHOW\"");
    if (!events.containsKey(id)) {
      events.put(id, Lists.newArrayList());
    }
    events.get(id).add(result);
    return new ServiceEventBuilder<>(result);
  }
}
