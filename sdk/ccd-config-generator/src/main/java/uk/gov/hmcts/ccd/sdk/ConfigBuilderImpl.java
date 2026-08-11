package uk.gov.hmcts.ccd.sdk;

import static uk.gov.hmcts.ccd.sdk.api.Event.ATTACH_SCANNED_DOCS;
import static uk.gov.hmcts.ccd.sdk.api.Event.HANDLE_EVIDENCE;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.api.AccessType;
import uk.gov.hmcts.ccd.sdk.api.AccessType.AccessTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.AccessTypeRole;
import uk.gov.hmcts.ccd.sdk.api.AccessTypeRole.AccessTypeRoleBuilder;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.CaseCategory.CaseCategoryBuilder;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile.CaseRoleToAccessProfileBuilder;
import uk.gov.hmcts.ccd.sdk.api.ComplexTypeAuthorisation;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
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
  final List<AccessTypeBuilder> accessTypes = Lists.newArrayList();
  final List<AccessTypeRoleBuilder> accessTypeRoles = Lists.newArrayList();
  final List<SearchCriteriaBuilder> searchCriteria = Lists.newArrayList();
  final List<SearchPartyBuilder> searchParty = Lists.newArrayList();
  final Set<R> omitHistoryForRoles = new HashSet<>();
  final List<ComplexTypeAuthorisation<R>> complexTypeAuthorisations = Lists.newArrayList();
  private NoticeOfChangeBuilder<T, R> noticeOfChangeBuilder;

  public ConfigBuilderImpl(ResolvedCCDConfig<T, S, R> config) {
    this.config = config;
  }

  <X, Y> List<Y> buildBuilders(Collection<X> c, Function<X, Y> f) {
    return c.stream().map(f).collect(Collectors.toList());
  }

  public ResolvedCCDConfig<T, S, R> build() {
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
    config.accessTypes = buildBuilders(accessTypes, AccessTypeBuilder::build);
    config.accessTypeRoles = buildBuilders(accessTypeRoles, AccessTypeRoleBuilder::build);
    deriveAccessTypesFromRoles();
    config.searchCriteria = buildBuilders(searchCriteria, SearchCriteriaBuilder::build);
    config.searchParties = buildBuilders(searchParty, SearchPartyBuilder::build);
    config.noticeOfChange = noticeOfChangeBuilder == null ? null : noticeOfChangeBuilder.build(config.caseType);
    config.complexTypeAuthorisations = Lists.newArrayList(complexTypeAuthorisations);

    return config;
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
  public void stateLabel(String stateId, String label) {
    config.stateLabel(stateId, label);
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
  public void shutterServiceExclude(R... roles) {
    config.shutterServiceExcludedRoles.addAll(Set.of(roles));
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
    var builder = CaseRoleToAccessProfileBuilder.<R>builder(caseRole);
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
  public AccessTypeBuilder accessType(String accessTypeId) {
    var builder = AccessTypeBuilder.builder(accessTypeId);
    accessTypes.add(builder);
    return builder;
  }

  @Override
  public AccessTypeRoleBuilder accessTypeRole(String accessTypeId) {
    var builder = AccessTypeRoleBuilder.builder(accessTypeId);
    accessTypeRoles.add(builder);
    return builder;
  }

  /**
   * Translate any {@link CCDAccessGroup} attached to a role enum constant into the same
   * {@code AccessType} / {@code AccessTypeRole} model objects the explicit builder calls produce,
   * so the existing generators emit identical JSON. Explicit builder rows take precedence: an
   * access type already configured via {@link #accessType} is not re-added.
   *
   * <p>Each attaching role also gets a {@code RoleToAccessProfiles} row, resolving to
   * {@link HasRole#getAccessProfiles()}. The definition store does not validate
   * {@code GroupRoleName} against that sheet, so an unmapped group role imports cleanly and then
   * grants nothing at runtime; deriving the row makes that failure impossible rather than silent. A
   * row the config already declared for the same role wins, so a team needing non-default
   * authorisations or access profiles can still declare it by hand.
   *
   * <p>An access type is keyed on {@code (accessTypeId, organisationProfileId)} throughout, matching
   * the definition store: {@code AccessTypesValidator} groups by
   * {@code AccessTypeEntity.UniqueIdentifier(caseType, jurisdiction, accessTypeId,
   * organisationProfileId)}. Keying on {@code accessTypeId} alone silently drops the same logical
   * access type offered to a second organisation profile.
   */
  private void deriveAccessTypesFromRoles() {
    R[] roles = config.getRoleClass().getEnumConstants();
    if (roles == null) {
      return;
    }

    Set<List<String>> existingAccessTypeKeys = config.accessTypes.stream()
        .map(ConfigBuilderImpl::accessTypeKey)
        .collect(Collectors.toCollection(HashSet::new));
    Set<String> mappedRoleNames = config.caseRoleToAccessProfiles.stream()
        .map(profile -> profile.getRole().getRole())
        .collect(Collectors.toCollection(HashSet::new));

    Map<List<String>, AccessType> derivedAccessTypes = new LinkedHashMap<>();
    List<AccessTypeRole> derivedRoles = Lists.newArrayList();

    for (R role : roles) {
      for (CCDAccessGroup group : role.getAccessGroups()) {
        if (group == null) {
          continue;
        }

        List<String> key = List.of(group.getAccessTypeId(),
            Strings.nullToEmpty(group.getOrganisationProfileId()));
        if (!existingAccessTypeKeys.contains(key)) {
          derivedAccessTypes.putIfAbsent(key, AccessType.builder()
              .accessTypeId(group.getAccessTypeId())
              .organisationProfileId(group.getOrganisationProfileId())
              .accessMandatory(group.isAccessMandatory())
              .accessDefault(group.isAccessDefault())
              .display(group.isDisplay())
              .description(group.getDescription())
              .hintText(group.getHintText())
              .displayOrder(group.getDisplayOrder())
              .liveTo(group.getLiveTo())
              .build());
        }

        AccessTypeRoleBuilder rowBuilder = AccessTypeRole.builder()
            .accessTypeId(group.getAccessTypeId())
            .organisationProfileId(group.getOrganisationProfileId())
            .caseAssignedRoleField(group.getCaseAssignedRoleField())
            .groupAccessEnabled(group.isGroupAccessEnabled())
            .caseAccessGroupIdTemplate(group.getCaseAccessGroupIdTemplate())
            .liveTo(group.getLiveTo());
        if (group.isGroupAccessEnabled()) {
          rowBuilder.groupRoleName(role.getRole());
        } else {
          rowBuilder.organisationalRoleName(role.getRole());
        }
        derivedRoles.add(rowBuilder.build());

        if (mappedRoleNames.add(role.getRole())) {
          config.caseRoleToAccessProfiles.add(
              CaseRoleToAccessProfileBuilder.<R>builder(role).build());
        }
      }
    }

    config.accessTypes.addAll(derivedAccessTypes.values());
    config.accessTypeRoles.addAll(derivedRoles);
  }

  private static List<String> accessTypeKey(AccessType accessType) {
    return List.of(accessType.getAccessTypeId(),
        Strings.nullToEmpty(accessType.getOrganisationProfileId()));
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
  public NoticeOfChangeBuilder<T, R> noticeOfChange() {
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
        result.put(event.getId(), event);
      }
    }

    return ImmutableMap.copyOf(result);
  }

}
