package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.CaseFormat;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.RoleBuilder;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket.WorkBasketBuilder;

public class ConfigBuilderImpl<T, S, R extends HasRole> implements ConfigBuilder<T, S, R> {

  private final ImmutableSet<S> allStates;
  public String caseType = "";
  public final SetMultimap<S, R> stateRoleHistoryAccess = HashMultimap.create();
  public final Table<S, R, Set<Permission>> stateRolePermissions = HashBasedTable.create();
  public final Map<String, String> statePrefixes = Maps.newHashMap();
  public final Set<String> apiOnlyRoles = Sets.newHashSet();
  public final Map<String, List<Event.EventBuilder<T, R, S>>> events = Maps.newHashMap();
  public final List<Field.FieldBuilder> explicitFields = Lists.newArrayList();
  public final List<TabBuilder> tabs = Lists.newArrayList();
  public final List<WorkBasketBuilder> workBasketResultFields = Lists.newArrayList();
  public final List<WorkBasketBuilder> workBasketInputFields = Lists.newArrayList();
  public final List<SearchBuilder> searchResultFields = Lists.newArrayList();
  public final List<SearchBuilder> searchInputFields = Lists.newArrayList();
  public final Map<String, String> roleHierarchy = new Hashtable<>();

  private Class caseData;
  public String environment;
  public String jurId = "";
  public String jurName = "";
  public String jurDesc = "";
  public String caseName = "";
  public String caseDesc = "";
  public String callbackHost;

  private String defaultWebhookConvention(Webhook webhook, String eventId) {
    eventId = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, eventId);
    String path = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, webhook.toString());
    return "/" + eventId + "/" + path;
  }

  public ConfigBuilderImpl(Class caseData, Set<S> allStates) {
    this.caseData = caseData;
    this.allStates = ImmutableSet.copyOf(allStates);
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
      public Event.EventBuilder<T, R, S> forAllStates() {
        return build(allStates, allStates);
      }

      @Override
      public Event.EventBuilder<T, R, S> forStates(S... states) {
        return build(Set.of(states), allStates);
      }

      private Event.EventBuilder<T, R, S> build(Set<S> preStates, Set<S> postStates) {
        Event.EventBuilder<T, R, S> result = Event.EventBuilder
            .builder(id, caseData, new PropertyUtils(), preStates, postStates);
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
    this.caseType = caseType;
    this.caseName = name;
    this.caseDesc = desc;
  }

  @Override
  public void jurisdiction(String id, String name, String description) {
    this.jurId = id;
    this.jurName = name;
    this.jurDesc = description;
  }

  @Override
  public void setEnvironment(String env) {
    this.environment = env;
  }

  @Override
  public void grant(S state, Set<Permission> permissions, R... roles) {
    for (R role : roles) {
      stateRolePermissions.put(state, role, permissions);
    }
  }

  @Override
  public void grantHistory(S state, R... roles) {
    for (R role : roles) {
      stateRoleHistoryAccess.put(state, role);
    }
  }

  @Override
  public void prefix(S state, String prefix) {
    statePrefixes.put(state.toString(), prefix);
  }

  @Override
  public FieldBuilder<?, ?, ?, ?> field(String id) {
    FieldBuilder builder = FieldBuilder
        .builder(caseData, null, id);
    explicitFields.add(builder);
    return builder;
  }

  @Override
  public void caseField(String id, String showCondition, String type, String typeParam,
                        String label) {
    field(id).label(label).type(type).fieldTypeParameter(typeParam);
  }

  @Override
  public void caseField(String id, String label, String type, String collectionType) {
    caseField(id, null, type, collectionType, label);
  }

  @Override
  public void caseField(String id, String label, String type) {
    caseField(id, label, type, null);
  }

  @Override
  public TabBuilder<T, R> tab(String tabId, String tabLabel) {
    TabBuilder<T, R> result = Tab.TabBuilder.builder(caseData,
        new PropertyUtils()).tabID(tabId).label(tabLabel);
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
  public RoleBuilder<R> role(R... roles) {
    return new RoleBuilder<R>() {
      @Override
      public void has(R parent) {
        for (R role : roles) {
          roleHierarchy.put(role.getRole(), parent.getRole());
        }
      }

      @Override
      public void setApiOnly() {
        for (R role : roles) {
          apiOnlyRoles.add(role.getRole());
        }
      }
    };
  }

  @Override
  public void setCallbackHost(String s) {
    this.callbackHost = s;
  }

  private WorkBasketBuilder getWorkBasketBuilder(List<WorkBasketBuilder> workBasketInputFields) {
    WorkBasketBuilder result = WorkBasketBuilder.builder(caseData, new PropertyUtils());
    workBasketInputFields.add(result);
    return result;
  }

  private SearchBuilder getSearchBuilder(List<SearchBuilder> searchInputFields) {
    SearchBuilder result = SearchBuilder.builder(caseData, new PropertyUtils());
    searchInputFields.add(result);
    return result;
  }

  public List<Event<T, R, S>> getEvents() {
    Map<String, Event<T, R, S>> result = Maps.newHashMap();
    for (Map.Entry<String, List<Event.EventBuilder<T, R, S>>> cell : events.entrySet()) {
      for (Event.EventBuilder<T, R, S> builder : cell.getValue()) {
        Event<T, R, S> event = builder.build();
        if (result.containsKey(event.getId())) {

          S s = event.getPostState().iterator().next();
          String namespace = statePrefixes.getOrDefault(s, "") + s;
          String stateSpecificId =
              event.getEventID() + namespace;
          if (result.containsKey(stateSpecificId)) {
            throw new RuntimeException("Duplicate event:" + stateSpecificId);
          }
          event.setId(stateSpecificId);
          event.setNamespace(namespace);
        }
        result.put(event.getId(), event);
      }
    }

    return Lists.newArrayList(result.values());
  }

}
