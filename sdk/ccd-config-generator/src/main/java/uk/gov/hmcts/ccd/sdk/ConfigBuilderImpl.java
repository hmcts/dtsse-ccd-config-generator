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
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteria.SearchCriteriaBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchParty.SearchPartyBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
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
    config.searchCriteria = buildBuilders(searchCriteria, SearchCriteriaBuilder::build);
    config.searchParties = buildBuilders(searchParty, SearchPartyBuilder::build);

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
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <D> EventTypeBuilder<D, R, S> decentralisedEvent(String id, Class<D> dtoClass, Submit<D, S> submitHandler) {
    return new DtoEventTypeBuilderImpl<>(config, (Map) events, id, dtoClass,
        camelCaseInitials(id), submitHandler, null);
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <D> EventTypeBuilder<D, R, S> decentralisedEvent(
      String id, Class<D> dtoClass, Submit<D, S> submitHandler, Start<D, S> startHandler) {
    return new DtoEventTypeBuilderImpl<>(config, (Map) events, id, dtoClass,
        camelCaseInitials(id), submitHandler, startHandler);
  }

  /**
   * Extracts the first character of each camelCase word from an event ID.
   * e.g. "createPossessionClaim" → "cpc", "submitApplication" → "sa"
   */
  static String camelCaseInitials(String eventId) {
    if (eventId == null || eventId.isEmpty()) {
      throw new IllegalArgumentException("Event ID must not be null or empty");
    }
    StringBuilder initials = new StringBuilder();
    initials.append(Character.toLowerCase(eventId.charAt(0)));
    for (int i = 1; i < eventId.length(); i++) {
      if (Character.isUpperCase(eventId.charAt(i))) {
        initials.append(Character.toLowerCase(eventId.charAt(i)));
      }
    }
    return initials.toString();
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
