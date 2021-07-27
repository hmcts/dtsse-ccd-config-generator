package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;

@RequiredArgsConstructor
@Getter
public class ResolvedCCDConfig<T, S, R extends HasRole> {

  final Class<T> caseClass;
  final Class<S> stateClass;
  final Class<R> roleClass;
  final Map<Class, Integer> types;
  final ImmutableSet<S> allStates;

  Set<String> rolesWithNoHistory;
  String caseType = "";
  String callbackHost = "";
  String caseName = "";
  String caseDesc = "";
  String jurId = "";
  String jurName = "";
  String jurDesc = "";

  Table<S, R, Set<Permission>> stateRolePermissions = HashBasedTable.create();
  Map<String, String> roleHierarchy = Maps.newHashMap();

  // Events by id
  ImmutableMap<String, Event<T, R, S>> events;
  List<Tab<T, R>> tabs;
  List<WorkBasket> workBasketResultFields;
  List<WorkBasket> workBasketInputFields;
  List<Search> searchResultFields;
  List<Search> searchInputFields;
  List<SearchCases> searchCaseResultFields;
}
