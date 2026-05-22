package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CaseCategory;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile;
import uk.gov.hmcts.ccd.sdk.api.ComplexTypeAuthorisation;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteria;
import uk.gov.hmcts.ccd.sdk.api.SearchParty;
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
  String hmctsServiceId = "";
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
  List<CaseCategory> categories;
  List<SearchCriteria> searchCriteria;
  List<SearchParty> searchParties;
  NoticeOfChange<T, R> noticeOfChange;
  List<ComplexTypeAuthorisation<R>> complexTypeAuthorisations;

  public void addEvents(Map<String, Event<T, R, S>> additionalEvents) {
    if (additionalEvents.isEmpty()) {
      return;
    }

    Map<String, Event<T, R, S>> merged = new LinkedHashMap<>();
    if (events != null) {
      merged.putAll(events);
    }
    for (Map.Entry<String, Event<T, R, S>> entry : additionalEvents.entrySet()) {
      if (merged.containsKey(entry.getKey())) {
        throw new IllegalStateException(
            "Event %s is already defined for case type %s".formatted(entry.getKey(), caseType));
      }
      merged.put(entry.getKey(), entry.getValue());
    }
    events = ImmutableMap.copyOf(merged);
  }
}
