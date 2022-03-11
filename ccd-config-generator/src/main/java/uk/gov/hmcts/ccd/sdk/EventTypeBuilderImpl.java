package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@AllArgsConstructor
public class EventTypeBuilderImpl<T, R extends HasRole, S> implements EventTypeBuilder<T, R, S> {

  protected final ResolvedCCDConfig<T, S, R> config;
  protected final Map<String, List<Event.EventBuilder<T, R, S>>> events;
  protected final String id;

  @Override
  public Event.EventBuilder<T, R, S> forState(S state) {
    return build(Set.of(state), Set.of(state));
  }

  @Override
  public Event.EventBuilder<T, R, S> initialState(S state) {
    return build(Set.of(), Set.of(state));
  }

  @Override
  public Event.EventBuilder<T, R, S> forStateTransition(S from, S to) {
    return build(Set.of(from), Set.of(to));
  }

  @Override
  public Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, S to) {
    return build(from, Set.of(to));
  }

  @Override
  public Event.EventBuilder<T, R, S> forStateTransition(S from, EnumSet to) {
    return build(Set.of(from), to);
  }

  @Override
  public Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, EnumSet to) {
    return build(from, to);
  }

  @Override
  public Event.EventBuilder<T, R, S> forAllStates() {
    return build(config.allStates, config.allStates);
  }

  @Override
  public Event.EventBuilder<T, R, S> forStates(EnumSet states) {
    return build(states, states);
  }

  @Override
  public Event.EventBuilder<T, R, S> forStates(S... states) {
    return build(Set.of(states), Set.of(states));
  }

  protected Event.EventBuilder<T, R, S> build(Set<S> preStates, Set<S> postStates) {
    Event.EventBuilder<T, R, S> result = Event.EventBuilder
        .builder(id, config.caseClass, new PropertyUtils(), preStates, postStates);
    if (!events.containsKey(id)) {
      events.put(id, Lists.newArrayList());
    }
    events.get(id).add(result);
    return result;
  }
}

