package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;

import java.util.ArrayList;
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
import uk.gov.hmcts.ccd.sdk.types.HasState;
import uk.gov.hmcts.ccd.sdk.types.RoleBuilder;
import uk.gov.hmcts.ccd.sdk.types.Tab;
import uk.gov.hmcts.ccd.sdk.types.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.types.Webhook;
import uk.gov.hmcts.ccd.sdk.types.WebhookConvention;
import uk.gov.hmcts.ccd.sdk.types.WorkBasket.WorkBasketBuilder;

public class ConfigBuilderImpl<T, S extends HasState, R extends HasRole> implements ConfigBuilder<T, S, R> {

  public String caseType = "";
  public final Multimap<String, String> stateRoleHistoryAccess = ArrayListMultimap.create();
  public final Table<String, String, String> stateRolePermissions = HashBasedTable.create();
  public final Map<String, String> statePrefixes = Maps.newHashMap();
  public final Set<String> apiOnlyRoles = Sets.newHashSet();
  public final Set<String> noFieldAuthRoles = Sets.newHashSet();
//  public final Table<String, String, List<Event.EventBuilder<T, R, S>>> events = HashBasedTable
//      .create();
  public final List<Event.EventBuilder<T, R, S>> events = new ArrayList();
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
    stateRolePermissions.put(state.getState(), role.getRole(), permissions);
  }

  @Override
  public void grantHistory(S state, R... roles) {
    for (R role : roles) {
      stateRoleHistoryAccess.put(state.toString(), role.getRole());
    }
  }

  @Override
  public void prefix(S state, String prefix) {
    statePrefixes.put(state.toString(), prefix);
  }

  @Override
  public FieldBuilder<?, ?, ?> field(String id) {
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
  public void setWebhookConvention(WebhookConvention convention) {
    this.webhookConvention = convention;
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

      @Override
      public void noCaseEventToField() {
        for (R role : roles) {
          noFieldAuthRoles.add(role.getRole());
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
    for (Event.EventBuilder<T, R, S> builder : events) {
      Event<T, R, S> event = builder.build();
      if (result.containsKey(event.getId())) {
        event.setId(event.getEventID());
      }
      if (event.getPreState().isEmpty()) {
        event.setPreState(null);
      }
      result.put(event.getId(), event);
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
      return add(builder.forState(state));
    }

    @Override
    public Event.EventBuilder<T, R, S> initialState(S state) {
      return add(builder.initialState(state));
    }

    @Override
    public Event.EventBuilder<T, R, S> forStateTransition(S from, S to) {
      return add(builder.forStateTransition(from, to));
    }

    @Override
    public Event.EventBuilder<T, R, S> forStateTransition(List<S> from, S to) {
      return add(builder.forStateTransition(from, to));
    }



    @Override
    public Event.EventBuilder<T, R, S> forAllStates() {
      return add(builder.forAllStates());
    }

    @Override
    public Event.EventBuilder<T, R, S> forStates(S... states) {
      return add(builder.forStates(states));
    }

    private Event.EventBuilder<T,R,S> add(Event.EventBuilder<T,R,S> eventBuilder) {
      events.add(builder);
      return builder;
    }

  }
}
