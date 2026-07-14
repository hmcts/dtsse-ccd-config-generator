package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import uk.gov.hmcts.ccd.sdk.api.CaseType;
import uk.gov.hmcts.ccd.sdk.api.ComplexTypeAuthorisation;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Jurisdiction;
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
  Map<Class, Integer> types;
  final ImmutableSet<S> allStates;

  Set<String> rolesWithNoHistory;
  Set<R> shutterServiceForRoles = new HashSet<>();
  Set<R> shutterServiceExcludedRoles = new HashSet<>();
  String caseType = "";
  CaseType caseTypeDefinition;
  String callbackHost = "";
  String caseName = "";
  String caseDesc = "";
  String jurId = "";
  String jurName = "";
  String jurDesc = "";
  Jurisdiction jurisdictionDefinition;
  String hmctsServiceId = "";
  boolean includeDefaultLiveFrom = true;
  boolean includeCaseHistory = true;
  String caseHistoryLabel = " ";
  boolean shutterService = false;
  Map<String, List<? extends Enum<?>>> fixedLists = new LinkedHashMap<>();
  Map<String, String> stateLabels = new HashMap<>();
  Class<?> schemaProfile;
  Set<R> applicableRoles;
  boolean legacyCaseAuthorisationIdColumn;
  Set<R> caseTypeAuthorisationRolesWithLiveFrom = new HashSet<>();

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

  /** Backwards-compatible constructor for callers which pre-resolve complex types. */
  public ResolvedCCDConfig(
      Class<T> caseClass,
      Class<S> stateClass,
      Class<R> roleClass,
      Map<Class, Integer> types,
      ImmutableSet<S> allStates
  ) {
    this(caseClass, stateClass, roleClass, allStates);
    this.types = types;
  }

  public Optional<String> labelForState(String stateId) {
    return Optional.ofNullable(stateLabels.get(stateId));
  }

  public void stateLabel(String stateId, String label) {
    if (label != null && !label.isBlank()) {
      stateLabels.put(stateId, label);
    }
  }

  public boolean isApplicableRole(HasRole role) {
    return applicableRoles == null || applicableRoles.contains(role);
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
