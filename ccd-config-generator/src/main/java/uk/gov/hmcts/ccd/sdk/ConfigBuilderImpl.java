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
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile.CaseRoleToAccessProfileBuilder;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;

public class ConfigBuilderImpl<T, S, R extends HasRole> implements ConfigBuilder<T, S, R> {

  private final ResolvedCCDConfig<T, S, R> config;

  final Map<String, List<Event.EventBuilder<T, R, S>>> events = Maps.newHashMap();
  final List<TabBuilder<T, R>> tabs = Lists.newArrayList();
  final List<SearchBuilder<T, R>> workBasketResultFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> workBasketInputFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> searchResultFields = Lists.newArrayList();
  final List<SearchBuilder<T, R>> searchInputFields = Lists.newArrayList();
  final List<SearchCasesBuilder<T>> searchCaseResultFields = Lists.newArrayList();
  final List<CaseRoleToAccessProfileBuilder<R>> caseRoleToAccessProfiles = Lists.newArrayList();
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

    return config;
  }

  @Override
  public EventTypeBuilderImpl<T, R, S> event(final String id) {
    return new EventTypeBuilderImpl<>(config, events, id);
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
            new PropertyUtils()).tabID(tabId).labelText(tabLabel);
    tabs.add(result);
    return result;
  }

  @Override
  public SearchBuilder<T, R> workBasketResultFields() {
    return getWorkBasketBuilder(workBasketResultFields);
  }

  @Override
  public SearchBuilder<T, R> workBasketInputFields() {
    return getWorkBasketBuilder(workBasketInputFields);
  }

  @Override
  public SearchBuilder<T, R> searchResultFields() {
    return getSearchBuilder(searchResultFields);
  }

  @Override
  public SearchBuilder<T, R> searchInputFields() {
    return getSearchBuilder(searchInputFields);
  }

  @Override
  public SearchCasesBuilder<T> searchCasesFields() {
    return getSearchCasesBuilder(searchCaseResultFields);
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

  private SearchBuilder<T, R> getWorkBasketBuilder(List<SearchBuilder<T, R>> workBasketInputFields) {
    SearchBuilder<T, R> result = SearchBuilder.builder(config.caseClass, new PropertyUtils());
    workBasketInputFields.add(result);
    return result;
  }

  private SearchBuilder<T, R> getSearchBuilder(List<SearchBuilder<T, R>> searchInputFields) {
    SearchBuilder<T, R> result = SearchBuilder.builder(config.caseClass, new PropertyUtils());
    searchInputFields.add(result);
    return result;
  }

  private SearchCasesBuilder<T> getSearchCasesBuilder(List<SearchCasesBuilder<T>> searchInputFields) {
    SearchCasesBuilder<T> result = SearchCasesBuilder.builder(config.caseClass, new PropertyUtils());
    searchInputFields.add(result);
    return result;
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
