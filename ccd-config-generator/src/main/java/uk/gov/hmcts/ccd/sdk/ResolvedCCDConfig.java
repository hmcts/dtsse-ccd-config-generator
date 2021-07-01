package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

public class ResolvedCCDConfig<T, S, R extends HasRole> {

  public final Class<?> typeArg;
  public final ConfigBuilderImpl<T, S, R> builder;
  public final List<Event<T, R, S>> events;
  public final Map<Class, Integer> types;
  public final Class<S> stateArg;
  public final Class<?> roleType;
  public final ImmutableSet<S> allStates;
  public final Map<String, AboutToStart<T, S>> aboutToStartCallbacks;
  public final Map<String, AboutToSubmit<T, S>> aboutToSubmitCallbacks;
  public final Map<String, Submitted<T, S>> submittedCallbacks;
  // Maps event ID and page ID to mid-event callbacks
  public final Table<String, String, MidEvent<T, S>> midEventCallbacks;

  public ResolvedCCDConfig(Class<?> typeArg, Class<S> stateArg, Class<?> roleType,
                           ConfigBuilderImpl<T, S, R> builder, List<Event<T, R, S>> events,
                           Map<Class, Integer> types,
                           Set<S> allStates,
                           Map<String, AboutToStart<T, S>> aboutToStartCallbacks,
                           Map<String, AboutToSubmit<T, S>> aboutToSubmitCallbacks,
                           Map<String, Submitted<T, S>> submittedCallbacks,
                           Table<String, String, MidEvent<T, S>> midEventCallbacks) {
    this.typeArg = typeArg;
    this.stateArg = stateArg;
    this.roleType = roleType;
    this.builder = builder;
    this.events = events;
    this.types = types;
    this.allStates = ImmutableSet.copyOf(allStates);
    this.aboutToStartCallbacks = aboutToStartCallbacks;
    this.aboutToSubmitCallbacks = aboutToSubmitCallbacks;
    this.submittedCallbacks = submittedCallbacks;
    this.midEventCallbacks = midEventCallbacks;
  }
}
