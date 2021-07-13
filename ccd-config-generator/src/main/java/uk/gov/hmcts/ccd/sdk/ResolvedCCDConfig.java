package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

@RequiredArgsConstructor
public class ResolvedCCDConfig<T, S, R extends HasRole> {

  public final String caseType;
  public final Class<?> typeArg;
  public final Class<S> stateArg;
  public final Class<?> roleType;
  public final ConfigBuilderImpl<T, S, R> builder;
  public final List<Event<T, R, S>> events;
  public final Map<Class, Integer> types;
  public final ImmutableSet<S> allStates;
  public final Map<String, AboutToStart<T, S>> aboutToStartCallbacks;
  public final Map<String, AboutToSubmit<T, S>> aboutToSubmitCallbacks;
  public final Map<String, Submitted<T, S>> submittedCallbacks;
  // Maps event ID and page ID to mid-event callbacks
  public final Table<String, String, MidEvent<T, S>> midEventCallbacks;
}
