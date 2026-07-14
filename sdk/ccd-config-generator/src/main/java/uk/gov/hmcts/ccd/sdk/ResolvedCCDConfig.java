package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
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
  Set<R> shutterServiceExcludedRoles = new HashSet<>();
  String caseType = "";
  String callbackHost = "";
  String caseName = "";
  String caseDesc = "";
  String jurId = "";
  String jurName = "";
  String jurDesc = "";
  String hmctsServiceId = "";
  boolean shutterService = false;
  boolean explicitStateGrants = false;
  Map<String, String> stateLabels = new HashMap<>();

  /**
   * CCD IDs of {@code @CCD(gate)} fields whose gate is inactive in the current environment. Empty
   * when nothing is gated off (the common case). Generators that place fields by ID — the
   * CaseEventToFields, AuthorisationCaseField event/tab/search, CaseTypeTab and search/work-basket
   * generators — skip a placement whose ID is in this set, so a gated-off field leaves no dangling
   * row referencing a CaseField the reflection filter already suppressed. Populated once at build
   * time (see {@code ConfigBuilderImpl.build}).
   */
  Set<String> gatedOffFieldIds = new HashSet<>();

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

  public Optional<String> labelForState(String stateId) {
    return Optional.ofNullable(stateLabels.get(stateId));
  }

  public void stateLabel(String stateId, String label) {
    if (label != null && !label.isBlank()) {
      stateLabels.put(stateId, label);
    }
  }

  void resolveStateLabels() {
    Object[] constants = stateClass.getEnumConstants();
    for (Object constant : constants) {
      String stateId = constant.toString();
      stateLabels.putIfAbsent(stateId, resolvedEnumStateLabel(stateId));
    }
  }

  private String resolvedEnumStateLabel(String stateId) {
    try {
      Field field = stateClass.getField(stateId);
      CCD ccd = field.getAnnotation(CCD.class);
      if (ccd != null && !ccd.label().isBlank()) {
        return ccd.label();
      }
    } catch (NoSuchFieldException ignored) {
      // Fall back to the state id if reflection cannot resolve the enum field.
    }
    return stateId;
  }
}
