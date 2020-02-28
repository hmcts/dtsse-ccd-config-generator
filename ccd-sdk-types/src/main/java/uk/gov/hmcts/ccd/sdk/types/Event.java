package uk.gov.hmcts.ccd.sdk.types;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.With;

@Builder
@Data
public class Event<T, R extends Role, S> {

  @With
  private String id;
  // The same event can have a different ID if on different states.
  private String eventId;

  private String name;
  private String preState;
  private String postState;
  private String description;
  private String aboutToStartURL;
  private String aboutToSubmitURL;
  private String submittedURL;
  private String midEventURL;
  private String retries;
  private boolean explicitGrants;
  private boolean showSummary;
  private boolean showEventNotes;
  private int eventNumber;

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
  private FieldCollection.FieldCollectionBuilder<T, ?> fields;

  @Builder.Default
  // TODO: don't always add.
  private String endButtonLabel = "Save and continue";
  @Builder.Default
  private int displayOrder = -1;

  private Map<String, String> grants;

  public Map<String, String> getGrants() {
    return grants;
  }

  private Class dataClass;
  private static int eventCount;


  public static class EventBuilder<T, R extends Role, S> {

    private WebhookConvention webhookConvention;

    public static <T, R extends Role, S> EventBuilder<T, R, S> builder(Class dataClass,
        WebhookConvention convention, PropertyUtils propertyUtils) {
      EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
      result.dataClass = dataClass;
      result.grants = new HashMap<>();
      result.fields = FieldCollection.FieldCollectionBuilder
          .builder(null, dataClass, propertyUtils);
      result.eventNumber = eventCount++;
      result.webhookConvention = convention;

      return result;
    }

    public FieldCollection.FieldCollectionBuilder<T, ?> fields() {
      return fields;
    }

    public EventBuilder<T, R, S> name(String n) {
      this.name = n;
      if (description == null) {
        description = n;
      }
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

    EventBuilder<T, R, S> forStates(S... states) {
      return forState(states[0]);
    }

    protected EventBuilder<T, R, S> forState(String state) {
      this.preState = state;
      this.postState = state;
      return this;
    }

    EventBuilder<T, R, S> forState(S state) {
      this.preState = state.toString();
      this.postState = state.toString();
      return this;
    }

    EventBuilder<T, R, S> postState(S state) {
      this.postState = state.toString();
      return this;
    }

    EventBuilder<T, R, S> preState(S state) {
      if (null != state) {
        this.preState = state.toString();
      }
      return this;
    }

    public EventBuilder<T, R, S> grant(String crud, R... roles) {
      for (R role : roles) {
        grants.put(role.getRole(), crud);
      }

      return this;
    }

    private String customWebhookName;

    public EventBuilder<T, R, S> allWebhooks() {
      return allWebhooks(this.customWebhookName);
    }

    public EventBuilder<T, R, S> allWebhooks(String convention) {
      this.customWebhookName = convention;
      aboutToStartWebhook();
      aboutToSubmitWebhook();
      submittedWebhook();
      midEventWebhook();
      return this;
    }

    public EventBuilder<T, R, S> aboutToStartWebhook(String eventId) {
      this.customWebhookName = eventId;
      return aboutToStartWebhook();
    }

    public EventBuilder<T, R, S> aboutToStartWebhook() {
      // Use snake case event ID by convention
      aboutToStartURL = getWebhookPathByConvention(Webhook.AboutToStart);
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitWebhook(String eventId) {
      this.customWebhookName = eventId;
      aboutToSubmitURL = getWebhookPathByConvention(Webhook.AboutToSubmit);
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitWebhook() {
      aboutToSubmitURL = getWebhookPathByConvention(Webhook.AboutToSubmit);
      return this;
    }

    public EventBuilder<T, R, S> submittedWebhook() {
      submittedURL = getWebhookPathByConvention(Webhook.Submitted);
      return this;
    }

    public EventBuilder<T, R, S> midEventWebhook(String eventId) {
      this.customWebhookName = eventId;
      midEventURL = getWebhookPathByConvention(Webhook.MidEvent);
      return this;
    }

    public EventBuilder<T, R, S> midEventWebhook() {
      midEventURL = getWebhookPathByConvention(Webhook.MidEvent);
      return this;
    }

    public EventBuilder<T, R, S> retries(Integer... retries) {
      List<String> strings = Arrays.stream(retries).map(x -> x.toString())
          .collect(Collectors.toList());
      this.retries = String.join(",", strings);
      return this;
    }

    private String getWebhookPathByConvention(Webhook hook) {
      String id = customWebhookName != null ? customWebhookName
          : eventId;
      return webhookConvention.buildUrl(hook, id);
    }
  }
}
