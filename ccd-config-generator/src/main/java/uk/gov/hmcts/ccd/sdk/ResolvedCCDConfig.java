package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.CallbackHandler;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class ResolvedCCDConfig<T, S, R extends HasRole> {

  public final Class<?> typeArg;
  public final ConfigBuilderImpl<T, S, R> builder;
  public final List<Event<T, R, S>> events;
  public final Map<Class, Integer> types;
  public final String environment;
  public final Class<?> stateArg;
  public final Class<?> roleType;
  public final ImmutableSet<S> allStates;
  public final Map<String, CallbackHandler<?, ?>> aboutToSubmitCallbacks;

  public ResolvedCCDConfig(Class<?> typeArg, Class<?> stateArg, Class<?> roleType,
                           ConfigBuilderImpl<T, S, R> builder, List<Event<T, R, S>> events,
                           Map<Class, Integer> types, String environment,
                           Set<S> allStates,
                           Map<String, CallbackHandler<?, ?>> aboutToSubmitCallbacks) {
    this.typeArg = typeArg;
    this.stateArg = stateArg;
    this.roleType = roleType;
    this.builder = builder;
    this.events = events;
    this.types = types;
    this.environment = environment;
    this.allStates = ImmutableSet.copyOf(allStates);
    this.aboutToSubmitCallbacks = aboutToSubmitCallbacks;
  }
}
