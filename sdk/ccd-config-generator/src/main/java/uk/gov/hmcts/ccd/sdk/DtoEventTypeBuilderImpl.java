package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;

/**
 * EventTypeBuilder for decentralised events using isolated DTO classes.
 * Uses the DTO class as the data class for event field resolution,
 * and sets the DTO prefix on the FieldCollectionBuilder for auto-prefixing.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class DtoEventTypeBuilderImpl<D, R extends HasRole, S> implements EventTypeBuilder<D, R, S> {

  private final ResolvedCCDConfig config;
  private final Map<String, List<Event.EventBuilder>> events;
  private final String id;
  private final Class<D> dtoClass;
  private final String dtoPrefix;
  private final Submit<D, S> submitHandler;
  private final Start<D, S> startHandler;

  public DtoEventTypeBuilderImpl(ResolvedCCDConfig config,
                                 Map<String, List<Event.EventBuilder>> events,
                                 String id, Class<D> dtoClass, String dtoPrefix,
                                 Submit<D, S> submitHandler, Start<D, S> startHandler) {
    this.config = config;
    this.events = events;
    this.id = id;
    this.dtoClass = dtoClass;
    this.dtoPrefix = dtoPrefix;
    this.submitHandler = submitHandler;
    this.startHandler = startHandler;
  }

  @Override
  public Event.EventBuilder<D, R, S> forState(S state) {
    return build(Set.of(state), Set.of(state));
  }

  @Override
  public Event.EventBuilder<D, R, S> initialState(S state) {
    return build(Set.of(), Set.of(state));
  }

  @Override
  public Event.EventBuilder<D, R, S> forStateTransition(S from, S to) {
    return build(Set.of(from), Set.of(to));
  }

  @Override
  public Event.EventBuilder<D, R, S> forStateTransition(EnumSet from, S to) {
    return build(from, Set.of(to));
  }

  @Override
  public Event.EventBuilder<D, R, S> forStateTransition(S from, EnumSet to) {
    return build(Set.of(from), to);
  }

  @Override
  public Event.EventBuilder<D, R, S> forStateTransition(EnumSet from, EnumSet to) {
    return build(from, to);
  }

  @Override
  public Event.EventBuilder<D, R, S> forAllStates() {
    return build(config.getAllStates(), config.getAllStates());
  }

  @Override
  public Event.EventBuilder<D, R, S> forStates(EnumSet states) {
    return build(states, states);
  }

  @Override
  public Event.EventBuilder<D, R, S> forStates(S... states) {
    return build(Set.of(states), Set.of(states));
  }

  protected Event.EventBuilder<D, R, S> build(Set<S> preStates, Set<S> postStates) {
    Event.EventBuilder<D, R, S> result = Event.EventBuilder
        .builder(id, dtoClass, new PropertyUtils(), preStates, postStates, dtoClass, dtoPrefix);
    result.submitHandler(this.submitHandler);
    result.startHandler(this.startHandler);
    if (!events.containsKey(id)) {
      events.put(id, Lists.newArrayList());
    }
    events.get(id).add(result);
    return result;
  }
}
