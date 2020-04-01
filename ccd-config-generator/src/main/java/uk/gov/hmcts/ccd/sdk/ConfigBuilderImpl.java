package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.types.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.EventTypeBuilder;
import uk.gov.hmcts.ccd.sdk.types.Field;
import uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.types.HasRole;
import uk.gov.hmcts.ccd.sdk.types.RoleBuilder;
import uk.gov.hmcts.ccd.sdk.types.Tab;
import uk.gov.hmcts.ccd.sdk.types.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.types.Webhook;
import uk.gov.hmcts.ccd.sdk.types.WebhookConvention;
import uk.gov.hmcts.ccd.sdk.types.WorkBasket.WorkBasketBuilder;

public class ConfigBuilderImpl<T, S, R extends HasRole> implements ConfigBuilder<T, S, R> {

  public String caseType = "";
  public final Table<String, String, String> stateRolePermissions = HashBasedTable.create();
  public final Multimap<String, String> stateRoleblacklist = ArrayListMultimap.create();
  public final Map<String, String> statePrefixes = Maps.newHashMap();
  public final Set<String> apiOnlyRoles = Sets.newHashSet();
  public final Table<String, String, List<Event.EventBuilder<T, R, S>>> events = HashBasedTable
      .create();
  public final List<Field.FieldBuilder> explicitFields = Lists.newArrayList();
  public final List<TabBuilder> tabs = Lists.newArrayList();
  public final List<WorkBasketBuilder> workBasketResultFields = Lists.newArrayList();
  public final List<WorkBasketBuilder> workBasketInputFields = Lists.newArrayList();
  public final Map<String, String> roleHierarchy = new Hashtable<>();

  private Class caseData;
  private WebhookConvention webhookConvention = this::defaultWebhookConvention;
  public String environment;

  private String defaultWebhookConvention(Webhook webhook, String eventId) {
    eventId = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, eventId);
    String path = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, webhook.toString());
    return "/" + eventId + "/" + path;
  }

  public ConfigBuilderImpl(Class caseData) {
    this.caseData = caseData;
  }

  @Override
  public EventTypeBuilder<T, R, S> event(final String id) {
    Event.EventBuilder<T, R, S> e = Event.EventBuilder
        .builder(caseData, webhookConvention, new PropertyUtils());
    e.eventId(id);
    e.id(id);
    return new EventTypeBuilderImpl(e);
  }

  @Override
  public void caseType(String caseType) {
    this.caseType = caseType;
  }

  @Override
  public void setEnvironment(String env) {
    this.environment = env;
  }

  @Override
  public void grant(S state, String permissions, R role) {
    stateRolePermissions.put(state.toString(), role.getRole(), permissions);
  }

  @Override
  public void blacklist(S state, R... roles) {
    for (HasRole role : roles) {
      stateRoleblacklist.put(state.toString(), role.getRole());
    }
  }

  @Override
  public void prefix(S state, String prefix) {
    statePrefixes.put(state.toString(), prefix);
  }

  @Override
  public FieldBuilder<T, ?> field(String id) {
    FieldBuilder builder = FieldBuilder
        .builder(caseData, null, new PropertyUtils());
    explicitFields.add(builder);
    return builder.id(id);
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
  public void setWebhookConvention(WebhookConvention convention) {
    this.webhookConvention = convention;
  }

  @Override
  public TabBuilder tab(String tabId, String tabLabel) {
    TabBuilder result = Tab.TabBuilder.builder(caseData,
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
  public RoleBuilder<R> role(R... roles) {
    return new RoleBuilder<R>() {
      @Override
      public void has(R parent) {
        for (R role : roles) {
          roleHierarchy.put(role.getRole(), parent.getRole());
        }
      }

      @Override
      public void apiOnly() {
        for (R role : roles) {
          apiOnlyRoles.add(role.getRole());
        }
      }
    };
  }

  private WorkBasketBuilder getWorkBasketBuilder(List<WorkBasketBuilder> workBasketInputFields) {
    WorkBasketBuilder result = WorkBasketBuilder.builder(caseData, new PropertyUtils());
    workBasketInputFields.add(result);
    return result;
  }

  public List<Event<T, R, S>> getEvents() {
    Map<String, Event<T, R, S>> result = Maps.newHashMap();
    for (Table.Cell<String, String, List<Event.EventBuilder<T, R, S>>> cell : events.cellSet()) {
      for (Event.EventBuilder<T, R, S> builder : cell.getValue()) {
        Event<T, R, S> event = builder.build();
        event.setPreState(cell.getRowKey());
        event.setPostState(cell.getColumnKey());
        if (result.containsKey(event.getId())) {
          String namespace = statePrefixes.getOrDefault(cell.getColumnKey(), "") + cell
                  .getColumnKey();
          String stateSpecificId =
              event.getEventID() + namespace;
          if (result.containsKey(stateSpecificId)) {
            throw new RuntimeException("Duplicate event:" + stateSpecificId);
          }
          event.setId(stateSpecificId);
          event.setNamespace(namespace);
        }
        if (event.getPreState().isEmpty()) {
          event.setPreState(null);
        }
        result.put(event.getId(), event);
      }
    }

    return Lists.newArrayList(result.values());
  }

  public class EventTypeBuilderImpl implements EventTypeBuilder<T, R, S> {


    private final Event.EventBuilder<T, R, S> builder;

    public EventTypeBuilderImpl(Event.EventBuilder<T, R, S> builder) {
      this.builder = builder;
    }

    @Override
    public Event.EventBuilder<T, R, S> forState(S state) {
      add(state.toString(), state.toString());
      return builder;
    }

    @Override
    public Event.EventBuilder<T, R, S> initialState(S state) {
      add("", state.toString());
      return builder;
    }

    @Override
    public Event.EventBuilder<T, R, S> forStateTransition(S from, S to) {
      add(from.toString(), to.toString());
      return builder;
    }

    @Override
    public Event.EventBuilder<T, R, S> forAllStates() {
      add("*", "*");
      return builder;
    }

    @Override
    public Event.EventBuilder<T, R, S> forStates(S... states) {
      for (S state : states) {
        forState(state);
      }

      return builder;
    }

    private void add(String from, String to) {
      if (!events.contains(from, to)) {
        events.put(from, to, Lists.newArrayList());
      }
      events.get(from, to).add(builder);
    }
  }
}
