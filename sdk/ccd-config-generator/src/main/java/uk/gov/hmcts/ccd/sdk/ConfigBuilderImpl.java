package uk.gov.hmcts.ccd.sdk;

import static uk.gov.hmcts.ccd.sdk.api.Event.ATTACH_SCANNED_DOCS;
import static uk.gov.hmcts.ccd.sdk.api.Event.HANDLE_EVIDENCE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.api.CaseCategory.CaseCategoryBuilder;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile.CaseRoleToAccessProfileBuilder;
import uk.gov.hmcts.ccd.sdk.api.ComplexTypeAuthorisation;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange.NoticeOfChangeBuilder;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteria.SearchCriteriaBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchParty.SearchPartyBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;

public class ConfigBuilderImpl<T, S, R extends HasRole> implements DecentralisedConfigBuilder<T, S, R> {

  private final ResolvedCCDConfig<T, S, R> config;

  private final PropertyUtils propertyUtils = new PropertyUtils();

  final Map<String, List<Event.EventBuilder<T, R, S>>> events = Maps.newHashMap();
  final List<TabBuilder<T, R>> tabs = Lists.newArrayList();
  final List<SearchBuilder<T, R>> workBasketResultFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> workBasketInputFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> searchResultFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> searchInputFields = Lists.newArrayList();
  final List<SearchCasesBuilder<T>> searchCaseResultFields = Lists.newArrayList();
  final List<CaseRoleToAccessProfileBuilder<R>> caseRoleToAccessProfiles = Lists.newArrayList();
  final List<CaseCategoryBuilder<R>> categories = Lists.newArrayList();
  final List<SearchCriteriaBuilder> searchCriteria = Lists.newArrayList();
  final List<SearchPartyBuilder> searchParty = Lists.newArrayList();
  final Set<R> omitHistoryForRoles = new HashSet<>();
  final List<ComplexTypeAuthorisation<R>> complexTypeAuthorisations = Lists.newArrayList();
  private NoticeOfChangeBuilder<T, S, R> noticeOfChangeBuilder;

  public ConfigBuilderImpl(ResolvedCCDConfig<T, S, R> config) {
    this.config = config;
  }

  <X, Y> List<Y> buildBuilders(Collection<X> c, Function<X, Y> f) {
    return c.stream().map(f).collect(Collectors.toList());
  }

  public ResolvedCCDConfig<T, S, R> build() {
    config.noticeOfChange = noticeOfChangeBuilder == null ? null : noticeOfChangeBuilder.build();
    registerNoticeOfChangeEvent();
    config.events = getEvents();
    config.tabs = buildBuilders(tabs, TabBuilder::build);
    config.workBasketResultFields = buildBuilders(workBasketResultFields, SearchBuilder::build);
    config.workBasketInputFields = buildBuilders(workBasketInputFields, SearchBuilder::build);
    config.searchResultFields = buildBuilders(searchResultFields, SearchBuilder::build);
    config.searchInputFields = buildBuilders(searchInputFields, SearchBuilder::build);
    config.searchCaseResultFields = buildBuilders(searchCaseResultFields, SearchCasesBuilder::build);
    config.rolesWithNoHistory = omitHistoryForRoles.stream().map(HasRole::getRole).collect(Collectors.toSet());
    config.caseRoleToAccessProfiles = buildBuilders(caseRoleToAccessProfiles, CaseRoleToAccessProfileBuilder::build);
    config.categories = buildBuilders(categories, CaseCategoryBuilder::build);
    config.searchCriteria = buildBuilders(searchCriteria, SearchCriteriaBuilder::build);
    config.searchParties = buildBuilders(searchParty, SearchPartyBuilder::build);
    config.complexTypeAuthorisations = Lists.newArrayList(complexTypeAuthorisations);

    assertSingleNocApproverEvent();
    return config;
  }

  private static final String NOC_APPROVER_ROLE = "[NOCAPPROVER]";
  private static final String CASEWORKER_CAA_ROLE = "caseworker-caa";

  @SuppressWarnings("unchecked")
  private void registerNoticeOfChangeEvent() {
    NoticeOfChange<T, S, R> noc = config.noticeOfChange;
    if (noc == null || noc.getChallenges().isEmpty()) {
      return;
    }
    assertChangeOrganisationRequestFieldExists();

    Set<S> preStates = noc.getForStates().isEmpty() ? config.allStates : noc.getForStates();
    java.util.EnumSet<?> preStateEnumSet = java.util.EnumSet.copyOf(
        (Collection) preStates);

    if (!events.containsKey(noc.getEventId())) {
      R nocApprover = findRoleByName(NOC_APPROVER_ROLE,
          "[NOCAPPROVER] — the platform case role that aac auto-assigns during the NoC flow");
      EventTypeBuilderImpl<T, R, S> builder = new EventTypeBuilderImpl<>(
          config, events, noc.getEventId(), null, null);
      var event = builder.forStates((java.util.EnumSet) preStateEnumSet);
      event.name(noc.getEventName());
      event.grant(Permission.CRU, nocApprover);
      if (noc.getAboutToSubmitCallback() == null) {
        throw new IllegalStateException(
            "noticeOfChange() requires an aboutToSubmitCallback — that is where the service team "
                + "must call aac /noc/apply-decision to flip role assignments and apply the "
                + "new organisation policy. Without it, the approved change is never applied.");
      }
      event.aboutToSubmitCallback(noc.getAboutToSubmitCallback());
      if (noc.getAboutToStartCallback() != null) {
        event.aboutToStartCallback(noc.getAboutToStartCallback());
      }
      if (noc.getSubmittedCallback() != null) {
        event.submittedCallback(noc.getSubmittedCallback());
      }
    }

    if (!events.containsKey(noc.getRequestEventId())) {
      R caa = findRoleByName(CASEWORKER_CAA_ROLE,
          "caseworker-caa — the case access administrator role aac uses to populate the "
              + "ChangeOrganisationRequest field");
      EventTypeBuilderImpl<T, R, S> builder = new EventTypeBuilderImpl<>(
          config, events, noc.getRequestEventId(), null, null);
      var event = builder.forStates((java.util.EnumSet) preStateEnumSet);
      event.name(noc.getRequestEventName());
      event.grant(Permission.CRU, caa);
      // Register the event in config.events via the doBuild path:
      // we need the url override to reach the generated Event.
      // The EventTypeBuilderImpl registers an EventBuilder in `events`;
      // we stash the URL to apply after doBuild() in getEvents().
      requestEventIdsWithAacCallback.add(noc.getRequestEventId());
    }
  }

  // Event IDs whose submittedCallbackUrl should be set to aac's /noc/check-noc-approval
  // after doBuild(). Populated during registerNoticeOfChangeEvent.
  private final Set<String> requestEventIdsWithAacCallback = new HashSet<>();

  private R findRoleByName(String roleName, String description) {
    R[] roles = config.roleClass.getEnumConstants();
    if (roles == null) {
      throw new IllegalStateException("noticeOfChange() requires a role enum, but "
          + config.roleClass.getName() + " is not an enum");
    }
    for (R role : roles) {
      if (roleName.equals(role.getRole())) {
        return role;
      }
    }
    throw new IllegalStateException(
        "noticeOfChange() requires " + config.roleClass.getName()
            + " to declare a constant with getRole() == \"" + roleName + "\" — " + description);
  }

  private void assertChangeOrganisationRequestFieldExists() {
    String requiredType = uk.gov.hmcts.ccd.sdk.type.ChangeOrganisationRequest.class.getName();
    for (java.lang.reflect.Field field :
        uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields(config.caseClass)) {
      if (field.getType().getName().equals(requiredType)) {
        return;
      }
    }
    throw new IllegalStateException(
        "noticeOfChange() requires " + config.caseClass.getName()
            + " to declare a ChangeOrganisationRequest<R> field — it stores the incoming NoC "
            + "request from aac. Add: `@CCD ... private ChangeOrganisationRequest<UserRole> "
            + "changeOrganisationRequestField;`");
  }

  private void assertSingleNocApproverEvent() {
    if (config.noticeOfChange == null || config.noticeOfChange.getChallenges().isEmpty()) {
      return;
    }
    List<String> visibleEvents = Lists.newArrayList();
    for (Event<T, R, S> event : config.events.values()) {
      if (event.getGrants() == null) {
        continue;
      }
      boolean grantsNocApprover = event.getGrants().keySet().stream()
          .anyMatch(r -> NOC_APPROVER_ROLE.equals(r.getRole()));
      if (grantsNocApprover) {
        visibleEvents.add(event.getId());
      }
    }
    if (visibleEvents.size() > 1) {
      throw new IllegalStateException(
          "aac-manage-case-assignment requires exactly one event visible to [NOCAPPROVER], "
              + "but found: " + visibleEvents);
    }
  }

  @Override
  public EventTypeBuilderImpl<T, R, S> event(final String id) {
    return new EventTypeBuilderImpl<>(config, events, id, null, null);
  }

  @Override
  public EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler) {
    return new EventTypeBuilderImpl<>(config, events, id, submitHandler, null);
  }

  @Override
  public EventTypeBuilder<T, R, S> decentralisedEvent(String id, Submit<T, S> submitHandler, Start<T, S> startHandler) {
    return new EventTypeBuilderImpl<>(config, events, id, submitHandler, startHandler);
  }


  @Override
  public EventTypeBuilderImpl<T, R, S> attachScannedDocEvent() {
    return new BulkScanEventTypeBuilderImpl<>(config, events, ATTACH_SCANNED_DOCS, "Attach scanned docs");
  }

  @Override
  public EventTypeBuilderImpl<T, R, S> handleSupplementaryEvent() {
    return new BulkScanEventTypeBuilderImpl<>(config, events, HANDLE_EVIDENCE, "Handle supplementary evidence");
  }

  @Override
  public void caseType(String caseType, String name, String desc) {
    config.caseType = caseType;
    config.caseName = name;
    config.caseDesc = desc;
  }

  @Override
  public void jurisdiction(String id, String name, String description) {
    config.jurId = id;
    config.jurName = name;
    config.jurDesc = description;
  }

  @Override
  public void shutterService() {
    config.shutterService = true;
  }

  @Override
  public void shutterService(R... roles) {
    config.shutterServiceForRoles.addAll(Set.of(roles));
  }

  @Override
  public void omitHistoryForRoles(R... roles) {
    omitHistoryForRoles.addAll(Set.of(roles));
  }

  @Override
  public void grant(S state, Set<Permission> permissions, R... roles) {
    for (R role : roles) {
      config.stateRolePermissions.put(state, role, permissions);
    }
  }

  @Override
  public TabBuilder<T, R> tab(String tabId, String tabLabel) {
    TabBuilder<T, R> result = (TabBuilder<T, R>) TabBuilder.builder(config.caseClass,
        propertyUtils).tabID(tabId).labelText(tabLabel);
    tabs.add(result);
    return result;
  }

  @Override
  public SearchBuilder<T, R> workBasketResultFields() {
    return registerSearchBuilder(workBasketResultFields);
  }

  @Override
  public SearchBuilder<T, R> workBasketInputFields() {
    return registerSearchBuilder(workBasketInputFields);
  }

  @Override
  public SearchBuilder<T, R> searchResultFields() {
    return registerSearchBuilder(searchResultFields);
  }

  @Override
  public SearchBuilder<T, R> searchInputFields() {
    return registerSearchBuilder(searchInputFields);
  }

  @Override
  public SearchCasesBuilder<T> searchCasesFields() {
    return registerSearchCasesBuilder(searchCaseResultFields);
  }


  @Override
  public void setCallbackHost(String s) {
    config.callbackHost = s;
  }

  @Override
  public void hmctsServiceId(String value) {
    config.hmctsServiceId = value;
  }

  @Override
  public void addPreEventHook(
      Function<Map<String, Object>, Map<String, Object>> hook) {
    config.preEventHooks.add(hook);
  }

  @Override
  public CaseRoleToAccessProfileBuilder<R> caseRoleToAccessProfile(R caseRole) {
    var builder = CaseRoleToAccessProfileBuilder.builder(caseRole);
    caseRoleToAccessProfiles.add(builder);
    return builder;
  }

  @Override
  public CaseCategoryBuilder<R> categories(R caseRole) {
    var builder = CaseCategoryBuilder.builder(caseRole);
    categories.add(builder);
    return builder;
  }

  @Override
  public SearchCriteriaBuilder searchCriteria() {
    var builder = SearchCriteriaBuilder.builder();
    searchCriteria.add(builder);
    return builder;
  }

  @Override
  public SearchPartyBuilder searchParty() {
    var builder = SearchPartyBuilder.builder();
    searchParty.add(builder);
    return builder;
  }

  @Override
  public NoticeOfChangeBuilder<T, S, R> noticeOfChange() {
    if (noticeOfChangeBuilder == null) {
      noticeOfChangeBuilder = new NoticeOfChangeBuilder<>(config.caseClass, propertyUtils);
    }
    return noticeOfChangeBuilder;
  }

  @Override
  public void grantComplexType(TypedPropertyGetter<T, ?> field, String listElementCode,
                               Set<Permission> permissions, R... roles) {
    String caseFieldId = propertyUtils.getPropertyName(config.caseClass, field);
    for (R role : roles) {
      complexTypeAuthorisations.add(
          new ComplexTypeAuthorisation<>(caseFieldId, listElementCode, permissions, role));
    }
  }

  private SearchBuilder<T, R> registerSearchBuilder(List<SearchBuilder<T, R>> target) {
    SearchBuilder<T, R> builder = SearchBuilder.builder(config.caseClass, propertyUtils);
    target.add(builder);
    return builder;
  }

  private SearchCasesBuilder<T> registerSearchCasesBuilder(List<SearchCasesBuilder<T>> target) {
    SearchCasesBuilder<T> builder = SearchCasesBuilder.builder(config.caseClass, propertyUtils);
    target.add(builder);
    return builder;
  }

  ImmutableMap<String, Event<T, R, S>> getEvents() {
    Map<String, Event<T, R, S>> result = Maps.newHashMap();
    for (Map.Entry<String, List<Event.EventBuilder<T, R, S>>> cell : events.entrySet()) {
      for (Event.EventBuilder<T, R, S> builder : cell.getValue()) {
        Event<T, R, S> event = builder.doBuild();
        if (requestEventIdsWithAacCallback.contains(event.getId())) {
          event.setSubmittedCallbackUrl(NoticeOfChange.CHECK_NOC_APPROVAL_URL);
        }
        result.put(event.getId(), event);
      }
    }

    return ImmutableMap.copyOf(result);
  }

}
