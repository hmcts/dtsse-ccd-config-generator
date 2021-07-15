package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.RoleBuilder;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket.WorkBasketBuilder;

public class ConfigBuilderImpl<T, S, R extends HasRole> implements ConfigBuilder<T, S, R> {

  private final ResolvedCCDConfig<T, S, R> config;

  final Map<String, List<Event.EventBuilder<T, R, S>>> events = Maps.newHashMap();
  final List<TabBuilder<T, R>> tabs = Lists.newArrayList();
  final List<WorkBasketBuilder> workBasketResultFields = Lists.newArrayList();
  final List<WorkBasketBuilder> workBasketInputFields = Lists.newArrayList();
  final List<SearchBuilder> searchResultFields = Lists.newArrayList();
  final List<SearchBuilder> searchInputFields = Lists.newArrayList();
  final List<SearchCasesBuilder> searchCaseResultFields = Lists.newArrayList();

  public ConfigBuilderImpl(ResolvedCCDConfig<T, S, R> config) {
    this.config = config;
  }

  <X, Y> List<Y> buildBuilders(Collection<X> c, Function<X, Y> f) {
    return c.stream().map(f).collect(Collectors.toList());
  }

  public ResolvedCCDConfig<T, S, R> build() {
    config.events = getEvents();
    config.tabs = buildBuilders(tabs, TabBuilder::build);
    config.workBasketResultFields = buildBuilders(workBasketResultFields, WorkBasketBuilder::build);
    config.workBasketInputFields = buildBuilders(workBasketInputFields, WorkBasketBuilder::build);
    config.searchResultFields = buildBuilders(searchResultFields, SearchBuilder::build);
    config.searchInputFields = buildBuilders(searchInputFields, SearchBuilder::build);
    config.searchCaseResultFields = buildBuilders(searchCaseResultFields, SearchCasesBuilder::build);

    return config;
  }

  @Override
  public EventTypeBuilder<T, R, S> event(final String id) {
    return new EventTypeBuilder<>() {
      @Override
      public Event.EventBuilder<T, R, S> forState(S state) {
        return build(Set.of(state), Set.of(state));
      }

      @Override
      public Event.EventBuilder<T, R, S> initialState(S state) {
        return build(Set.of(), Set.of(state));
      }

      @Override
      public Event.EventBuilder<T, R, S> forStateTransition(S from, S to) {
        return build(Set.of(from), Set.of(to));
      }

      @Override
      public Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, S to) {
        return build(from, Set.of(to));
      }

      @Override
      public Event.EventBuilder<T, R, S> forAllStates() {
        return build(config.allStates, config.allStates);
      }

      @Override
      public Event.EventBuilder<T, R, S> forStates(S... states) {
        return build(Set.of(states), config.allStates);
      }

      private Event.EventBuilder<T, R, S> build(Set<S> preStates, Set<S> postStates) {
        Event.EventBuilder<T, R, S> result = Event.EventBuilder
            .builder(id, config.caseClass, new PropertyUtils(), preStates, postStates);
        if (!events.containsKey(id)) {
          events.put(id, Lists.newArrayList());
        }
        events.get(id).add(result);
        return result;
      }
    };
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
  public WorkBasketBuilder workBasketResultFields() {
    return getWorkBasketBuilder(workBasketResultFields);
  }

  @Override
  public WorkBasketBuilder workBasketInputFields() {
    return getWorkBasketBuilder(workBasketInputFields);
  }

  @Override
  public SearchBuilder searchResultFields() {
    return getSearchBuilder(searchResultFields);
  }

  @Override
  public SearchBuilder searchInputFields() {
    return getSearchBuilder(searchInputFields);
  }

  @Override
  public SearchCasesBuilder searchCasesFields() {
    return getSearchCasesBuilder(searchCaseResultFields);
  }


  @Override
  public RoleBuilder<R> role(R... roles) {
    return new RoleBuilder<R>() {
      @Override
      public void has(R parent) {
        for (R role : roles) {
          config.roleHierarchy.put(role.getRole(), parent.getRole());
        }
      }
    };
  }

  @Override
  public void setCallbackHost(String s) {
    config.callbackHost = s;
  }

  private WorkBasketBuilder getWorkBasketBuilder(List<WorkBasketBuilder> workBasketInputFields) {
    WorkBasketBuilder result = WorkBasketBuilder.builder(config.caseClass, new PropertyUtils());
    workBasketInputFields.add(result);
    return result;
  }

  private SearchBuilder getSearchBuilder(List<SearchBuilder> searchInputFields) {
    SearchBuilder result = SearchBuilder.builder(config.caseClass, new PropertyUtils());
    searchInputFields.add(result);
    return result;
  }

  private SearchCasesBuilder getSearchCasesBuilder(List<SearchCasesBuilder> searchInputFields) {
    SearchCasesBuilder result = SearchCasesBuilder.builder(config.caseClass, new PropertyUtils());
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
