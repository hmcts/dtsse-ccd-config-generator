package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasCaseRole;
import uk.gov.hmcts.ccd.sdk.api.HasCaseTypePerm;

public class ResolvedCCDConfig<T, S, R extends HasCaseTypePerm, C extends HasCaseRole> {

  public final Class<?> typeArg;
  public final ConfigBuilderImpl<T, S, R, C> builder;
  public final List<Event<T, R, S>> events;
  public final Map<Class<?>, Integer> types;
  public final String environment;
  public final Class<?> stateArg;
  public final Class<?> roleType;
  public final Class<C> caseRoleType;
  public final ImmutableSet<S> allStates;

  public ResolvedCCDConfig(Class<?> typeArg, Class<?> stateArg, Class<?> roleType, Class<C> caseRoleType,
                           ConfigBuilderImpl<T, S, R, C> builder, List<Event<T, R, S>> events,
                           Map<Class<?>, Integer> types, String environment,
                           Set<S> allStates) {
    this.typeArg = typeArg;
    this.stateArg = stateArg;
    this.roleType = roleType;
    this.caseRoleType = caseRoleType;
    this.builder = builder;
    this.events = events;
    this.types = types;
    this.environment = environment;
    this.allStates = ImmutableSet.copyOf(allStates);
  }
}
