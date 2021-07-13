package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;

@RequiredArgsConstructor
public class ResolvedCCDConfig<T, S, R extends HasRole> {

  public final String caseType;
  public final String callbackHost;
  public final String caseName;
  public final String caseDesc;
  public final String jurId;
  public final String jurName;
  public final String jurDesc;
  public final Class<?> typeArg;
  public final Class<S> stateArg;
  public final Class<?> roleType;
  // Events by id
  public final ImmutableMap<String, Event<T, R, S>> events;
  public final Map<Class, Integer> types;
  public final ImmutableSet<S> allStates;
  public final Table<S, R, Set<Permission>> stateRolePermissions;
  public final List<Tab<T, R>> tabs;
  public final List<WorkBasket> workBasketResultFields;
  public final List<WorkBasket> workBasketInputFields;
  public final List<Search> searchResultFields;
  public final List<Search> searchInputFields;
  public final List<SearchCases> searchCaseResultFields;
  public final ImmutableMap<String, String> roleHierarchy;
  public final List<Field> explicitFields;
}
