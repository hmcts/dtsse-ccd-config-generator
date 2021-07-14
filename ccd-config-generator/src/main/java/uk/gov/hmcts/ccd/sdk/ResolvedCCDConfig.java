package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;

@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PUBLIC)
public class ResolvedCCDConfig<T, S, R extends HasRole> {

  String caseType;
  String callbackHost;
  String caseName;
  String caseDesc;
  String jurId;
  String jurName;
  String jurDesc;
  Class<?> typeArg;
  Class<S> stateArg;
  Class<?> roleType;
  // Events by id
  ImmutableMap<String, Event<T, R, S>> events;
  Map<Class, Integer> types;
  ImmutableSet<S> allStates;
  Table<S, R, Set<Permission>> stateRolePermissions;
  List<Tab<T, R>> tabs;
  List<WorkBasket> workBasketResultFields;
  List<WorkBasket> workBasketInputFields;
  List<Search> searchResultFields;
  List<Search> searchInputFields;
  List<SearchCases> searchCaseResultFields;
  ImmutableMap<String, String> roleHierarchy;
  List<Field> explicitFields;
}
