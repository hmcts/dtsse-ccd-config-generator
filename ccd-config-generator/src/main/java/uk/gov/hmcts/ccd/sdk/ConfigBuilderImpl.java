package uk.gov.hmcts.ccd.sdk;

import static uk.gov.hmcts.ccd.sdk.api.Event.ATTACH_SCANNED_DOCS;
import static uk.gov.hmcts.ccd.sdk.api.Event.HANDLE_EVIDENCE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.api.CaseCategory.CaseCategoryBuilder;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile.CaseRoleToAccessProfileBuilder;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
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

public class ConfigBuilderImpl<T, S, R extends HasRole> implements ConfigBuilder<T, S, R> {

  private final ResolvedCCDConfig<T, S, R> config;

  final Map<String, EventTypeBuilderImpl<T, R, S>> events = Maps.newHashMap();
  final Map<String, EventTypeBuilderImpl<?, R, S>> decentralisedEvents = Maps.newHashMap();
  final Map<String, Class<?>> decentralisedEventTypes = Maps.newHashMap();
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
    config.decentralisedEvents = getDecentralisedEvents();
    config.decentralisedEventTypes = ImmutableMap.copyOf(decentralisedEventTypes);
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

  private ImmutableMap<String, Event<?, R, S>> getDecentralisedEvents() {
    Map<String, Event<?, R, S>> result = Maps.newHashMap();
    for (Map.Entry<String, EventTypeBuilderImpl<?, R, S>> e : this.decentralisedEvents.entrySet()) {
      result.put(e.getKey(), e.getValue().getResult().doBuild());
    }
    return ImmutableMap.copyOf(result);
  }

  @Override
  public EventTypeBuilderImpl<T, R, S> event(final String id) {
    EventTypeBuilderImpl<T, R, S> result = new EventTypeBuilderImpl<>(config.caseClass, config.allStates, id, null, null);
    events.put(id, result);
    return result;
  }

  @SneakyThrows
  @Override
  public <DTO> EventTypeBuilder<DTO, R, S> decentralisedEvent(String id, Submit<DTO, S> submitHandler) {
    return decentralisedEvent(id, submitHandler, null);
  }

  @SneakyThrows
  @Override
  public <DTO> EventTypeBuilder<DTO, R, S> decentralisedEvent(String id, Submit<DTO, S> submitHandler, Start<DTO, S> startHandler) {
    Method writeReplace = submitHandler.getClass().getDeclaredMethod("writeReplace");
    writeReplace.setAccessible(true);

    SerializedLambda serializedLambda = (SerializedLambda) writeReplace.invoke(submitHandler);

    String implClassName = serializedLambda.getImplClass().replace('/', '.');
    Class<?> implClass = Class.forName(implClassName);

    Method rez = null;

    for (Method method : implClass.getDeclaredMethods()) {
      if (Objects.equals(method.getName(), serializedLambda.getImplMethodName())) {
        rez = method;
      }
    }

    Type[] parameterTypes = rez.getGenericParameterTypes();

    Type firstParam = parameterTypes[0];

    ParameterizedType eventPayloadType = (ParameterizedType) firstParam;

    Type dtoType = eventPayloadType.getActualTypeArguments()[0];
    var dtoClass = (Class<DTO>) dtoType;
    decentralisedEventTypes.put(id, dtoClass);

    EventTypeBuilderImpl<DTO, R, S> result = new EventTypeBuilderImpl<>(dtoClass, config.allStates, id, submitHandler, startHandler);
    decentralisedEvents.put(id, result);

    return result;
  }


  @Override
  public EventTypeBuilderImpl<T, R, S> attachScannedDocEvent() {
    EventTypeBuilderImpl<T, R, S> result = new BulkScanEventTypeBuilderImpl<>(config, ATTACH_SCANNED_DOCS, "Attach scanned docs");
    events.put(ATTACH_SCANNED_DOCS, result);
    return result;

  }

  @Override
  public EventTypeBuilderImpl<T, R, S> handleSupplementaryEvent() {
    EventTypeBuilderImpl<T, R, S> result = new BulkScanEventTypeBuilderImpl<>(config, HANDLE_EVIDENCE, "Handle supplementary evidence");
    events.put(HANDLE_EVIDENCE, result);
    return result;
  }

  @Override
  public void decentralisedCaseType(String caseType, String name, String desc) {
    this.caseType(caseType, name, desc);
    config.decentralised = true;
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
    for (Map.Entry<String, EventTypeBuilderImpl<T, R, S>> e : this.events.entrySet()) {
      result.put(e.getKey(), e.getValue().getResult().doBuild());
    }
    return ImmutableMap.copyOf(result);
  }

}
