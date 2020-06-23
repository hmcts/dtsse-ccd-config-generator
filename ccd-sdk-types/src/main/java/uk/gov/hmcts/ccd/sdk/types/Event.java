package uk.gov.hmcts.ccd.sdk.types;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.With;

import static java.util.Arrays.stream;

@Builder
@Data
public class Event<T, R extends HasRole, S extends HasState> {

  @With
  private String id;
  // The same event can have a different ID if on different states.
  private String eventId;

  private String name;
  private List<String> preState;
  private String postState;
  private boolean transition;
  private String description;
  private String aboutToStartURL;
  private String aboutToSubmitURL;
  private String submittedURL;
  private Map<Webhook, String> retries;
  private boolean explicitGrants;
  private boolean showSummary;
  private boolean showEventNotes;
  private boolean showSummaryChangeOption;
  private int eventNumber;
  @Builder.Default
  private String namespace = "";

  public void setEventID(String eventId) {
    this.eventId = eventId;
  }

  public String getEventID() {
    return this.eventId != null ? this.eventId : this.id;
  }

  public void name(String s) {
    name = s;
    if (null == description) {
      description = s;
    }
  }

  @ToString.Exclude
  private FieldCollection.FieldCollectionBuilder<T, EventBuilder<T, R, S>> fields;

  @Builder.Default
  // TODO: don't always add.
  private String endButtonLabel = "Save and continue";
  @Builder.Default
  private int displayOrder = -1;

  private Map<String, String> grants;
  private Set<String> historyOnlyRoles;

  public Map<String, String> getGrants() {
    return grants;
  }

  public Set<String> getHistoryOnlyRoles() {
    return historyOnlyRoles;
  }

  private Class dataClass;
  private static int eventCount;

  public boolean isForAllStates() {
    return !isTransition() && preState != null && preState.size() > 0 && "*".equals(preState.get(0));
  }

  public boolean isTransition(){
    return transition;
  }

  public boolean isInitial() {
    return preState == null;
  }

  public boolean isMultiState() {
    return isForAllStates() || (!isInitial() && !isTransition() && preState.size() > 1);
  }

  public static class EventBuilder<T, R extends HasRole, S extends HasState> {

    private WebhookConvention webhookConvention;

    public static <T, R extends HasRole, S extends HasState> EventBuilder<T, R, S> builder(Class dataClass,
        WebhookConvention convention, PropertyUtils propertyUtils) {
      EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
      result.dataClass = dataClass;
      result.grants = new HashMap<>();
      result.historyOnlyRoles = new HashSet<>();
      result.fields = FieldCollection.FieldCollectionBuilder
          .builder(result, result, dataClass, propertyUtils);
      result.eventNumber = eventCount++;
      result.webhookConvention = convention;
      result.retries = new HashMap<>();

      return result;
    }

    public FieldCollection.FieldCollectionBuilder<T, EventBuilder<T, R, S>> fields() {
      return fields;
    }

    public EventBuilder<T, R, S> name(String n) {
      this.name = n;
      if (description == null) {
        description = n;
      }
      return this;
    }

    public EventBuilder<T, R, S> showSummaryChangeOption(boolean b) {
      this.showSummaryChangeOption = b;
      return this;
    }

    public EventBuilder<T, R, S> showSummaryChangeOption() {
      this.showSummaryChangeOption = true;
      return this;
    }

    public EventBuilder<T, R, S> showEventNotes() {
      this.showEventNotes = true;
      return this;
    }

    public EventBuilder<T, R, S> showSummary(boolean show) {
      this.showSummary = show;
      return this;
    }

    public EventBuilder<T, R, S> showSummary() {
      this.showSummary = true;
      return this;
    }

    // Do not inherit role permissions from states.
    public EventBuilder<T, R, S> explicitGrants() {
      this.explicitGrants = true;
      return this;
    }

    public EventBuilder<T, R, S> forState(S state) {
      this.preState = List.of(state.getState());
      this.postState = state.getState();
      return this;

    }
    public EventBuilder<T, R, S> initialState(S initialState) {
      this.preState = List.of();
      this.postState = initialState.getState();
      return this;
    }
    public EventBuilder<T, R, S> forStateTransition(S from, S to) {
      this.preState = List.of(from.getState());
      this.postState = to.getState();
      this.transition = true;
      return this;
    }

    public EventBuilder<T, R, S> forStateTransition(List<S> from, S to) {
      this.preState = from.stream().map(S::getState).collect(Collectors.toList());
      this.postState = to.getState();
      this.transition = true;
      return this;
    }


    public EventBuilder<T, R, S> forStates(S ... states) {
      this.preState = stream(states)
              .map(S::getState)
              .collect(Collectors.toList());
      this.postState = "*";
      return this;
    }
    public EventBuilder<T, R, S> forAllStates() {
      this.preState = List.of("*");
      this.postState = "*";
      return this;
    }


    public EventBuilder<T, R, S> grantHistoryOnly(R... roles) {
      for (R role : roles) {
        historyOnlyRoles.add(role.getRole());
      }
      grant("R", roles);

      return this;
    }

    public EventBuilder<T, R, S> grant(String crud, R... roles) {
      for (R role : roles) {
        grants.put(role.getRole(), crud);
      }

      return this;
    }

    String customWebhookName;

    public EventBuilder<T, R, S> allWebhooks() {
      return allWebhooks(this.customWebhookName);
    }

    public EventBuilder<T, R, S> allWebhooks(String convention) {
      this.customWebhookName = convention;
      aboutToStartWebhook();
      aboutToSubmitWebhook();
      submittedWebhook();
      return this;
    }

    public EventBuilder<T, R, S> aboutToStartWebhook(String eventId, int... retries) {
      this.customWebhookName = eventId;
      return aboutToStartWebhook(retries);
    }

    public EventBuilder<T, R, S> aboutToStartWebhook(int... retries) {
      // Use snake case event ID by convention
      aboutToStartURL = getWebhookPathByConvention(Webhook.AboutToStart);
      setRetries(Webhook.AboutToStart, retries);
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitWebhook(String eventId, int... retries) {
      this.customWebhookName = eventId;
      aboutToSubmitURL = getWebhookPathByConvention(Webhook.AboutToSubmit);
      setRetries(Webhook.AboutToSubmit, retries);
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitWebhook(int... retries) {
      aboutToSubmitURL = getWebhookPathByConvention(Webhook.AboutToSubmit);
      setRetries(Webhook.AboutToSubmit, retries);
      return this;
    }

    public EventBuilder<T, R, S> submittedWebhook(String eventId) {
      customWebhookName = eventId;
      submittedURL = getWebhookPathByConvention(Webhook.Submitted);
      return this;
    }

    public EventBuilder<T, R, S> submittedWebhook(int... retries) {
      submittedURL = getWebhookPathByConvention(Webhook.Submitted);
      setRetries(Webhook.Submitted, retries);
      return this;
    }

    public EventBuilder<T, R, S> retries(int... retries) {
      for (Webhook value : Webhook.values()) {
        setRetries(value, retries);
      }

      return this;
    }

    private void setRetries(Webhook hook, int... retries) {
      if (retries.length > 0) {
        String val = String.join(",", stream(retries).mapToObj(String::valueOf).collect(
            Collectors.toList()));
        this.retries.put(hook, val);
      }
    }

    String getWebhookPathByConvention(Webhook hook) {
      String id = customWebhookName != null ? customWebhookName
          : eventId;
      return webhookConvention.buildUrl(hook, id);
    }
  }
}
