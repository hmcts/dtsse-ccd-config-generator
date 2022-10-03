package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.Tab;

@RequiredArgsConstructor
@Getter
public class ResolvedCCDConfig<T, S, R extends HasRole> {

  final Class<T> caseClass;
  final Class<S> stateClass;
  final Class<R> roleClass;
  final Map<Class, Integer> types;
  final ImmutableSet<S> allStates;

  Set<String> rolesWithNoHistory;
  Set<R> shutterServiceForRoles = new HashSet<>();
  String caseType = "";
  String callbackHost = "";
  String caseName = "";
  String caseDesc = "";
  String jurId = "";
  String jurName = "";
  String jurDesc = "";
  boolean shutterService = false;

  Table<S, R, Set<Permission>> stateRolePermissions = HashBasedTable.create();

  // Events by id
  ImmutableMap<String, Event<T, R, S>> events;
  List<Function<Map<String, Object>, Map<String, Object>>> preEventHooks = new ArrayList<>();
  List<Tab<T, R>> tabs;
  List<Search<T, R>> workBasketResultFields;
  List<Search<T, R>> workBasketInputFields;
  List<Search<T, R>> searchResultFields;
  List<Search<T, R>> searchInputFields;
  List<SearchCases> searchCaseResultFields;
  List<CaseRoleToAccessProfile> caseRoleToAccessProfiles;
}
