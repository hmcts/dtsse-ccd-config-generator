package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

@Builder
@Data
public class Event<T, R extends HasRole, S> {

  public static final String ATTACH_SCANNED_DOCS = "attachScannedDocs";
  public static final String HANDLE_EVIDENCE = "handleEvidence";

  private String id;

  private String name;
  private Set<S> preState;
  private Set<S> postState;
  private boolean postStateWildcard;
  private String postStateExpression;
  private boolean inferStateAuthorisation;
  private String description;
  private String showCondition;
  private Map<Webhook, String> retries;
  private Map<Webhook, String> callbackUrls;
  private boolean explicitGrants;
  private boolean showSummary;
  private boolean showSummaryColumn;
  private boolean showEventNotes;
  private boolean showEventNotesColumn;
  private boolean publishToCamunda;
  private boolean publishColumn;
  private boolean displayOrderColumn;
  private Object ttlIncrement;
  private boolean significantEvent;
  private AboutToStart<T, S> aboutToStartCallback;
  private AboutToSubmit<T, S> aboutToSubmitCallback;
  private Submitted<T, S> submittedCallback;
  private Submit<T, S> submitHandler;
  private Start<T, S> startHandler;
  private FieldCollection fields;

  public void name(String s) {
    name = s;
    if (null == description) {
      description = s;
    }
  }

  @Builder.Default
  // TODO: don't always add.
  private String endButtonLabel = "Save and continue";

  @Builder.Default private int displayOrder = -1;

  private SetMultimap<R, Permission> grants;
  private Set<String> historyOnlyRoles;

  public Set<String> getHistoryOnlyRoles() {
    return historyOnlyRoles;
  }

  private Class dataClass;
  private static int eventCount;

  public static class EventBuilder<T, R extends HasRole, S> {

    private FieldCollection.FieldCollectionBuilder<T, S, EventBuilder<T, R, S>> fieldsBuilder;

    public static <T, R extends HasRole, S> EventBuilder<T, R, S> builder(
        String id,
        Class dataClass,
        PropertyUtils propertyUtils,
        Set<S> preStates,
        Set<S> postStates) {
      EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
      result.id(id);
      result.preState = preStates;
      result.postState = postStates;
      result.dataClass = dataClass;
      result.grants = HashMultimap.create();
      result.historyOnlyRoles = new HashSet<>();
      result.fieldsBuilder =
          FieldCollection.FieldCollectionBuilder.builder(result, result, dataClass, propertyUtils);
      result.retries = new HashMap<>();
      result.callbackUrls = new HashMap<>();
      result.showSummaryColumn = true;
      result.showEventNotesColumn = true;
      result.publishColumn = true;
      result.displayOrderColumn = true;
      result.inferStateAuthorisation = true;

      return result;
    }

    public Event<T, R, S> doBuild() {
      Event<T, R, S> result = build();
      // Complete the building of the nested builder.
      result.fields = fieldsBuilder.build();
      return result;
    }

    public FieldCollection.FieldCollectionBuilder<T, S, EventBuilder<T, R, S>> fields() {
      return fieldsBuilder;
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

    public EventBuilder<T, R, S> postStateWildcard() {
      this.postStateWildcard = true;
      return this;
    }

    /** Uses an existing CCD dynamic post-state expression. */
    public EventBuilder<T, R, S> postStateExpression(String expression) {
      if (expression == null || expression.isBlank()) {
        throw new IllegalArgumentException("Post-state expression must not be blank");
      }
      this.postStateExpression = expression;
      return this;
    }

    /** Keeps state authorisation fully explicit for this event. */
    public EventBuilder<T, R, S> omitStateAuthorisationInference() {
      this.inferStateAuthorisation = false;
      return this;
    }

    public EventBuilder<T, R, S> omitShowEventNotes() {
      this.showEventNotesColumn = false;
      return this;
    }

    public EventBuilder<T, R, S> omitEndButtonLabel() {
      endButtonLabel(null);
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

    public EventBuilder<T, R, S> omitShowSummary() {
      this.showSummaryColumn = false;
      return this;
    }

    public EventBuilder<T, R, S> publishToCamunda(boolean publishToCamunda) {
      this.publishToCamunda = publishToCamunda;
      return this;
    }

    public EventBuilder<T, R, S> publishToCamunda() {
      this.publishToCamunda = true;
      return this;
    }

    public EventBuilder<T, R, S> omitPublish() {
      this.publishColumn = false;
      return this;
    }

    public EventBuilder<T, R, S> omitDisplayOrder() {
      this.displayOrderColumn = false;
      return this;
    }

    public EventBuilder<T, R, S> ttlIncrement(Integer ttlIncrement) {
      this.ttlIncrement = ttlIncrement;
      return this;
    }

    /** Retains a legacy string-valued TTL increment during definition migration. */
    public EventBuilder<T, R, S> ttlIncrement(String ttlIncrement) {
      this.ttlIncrement = ttlIncrement;
      return this;
    }

    /** Marks an event as significant for CCD event-history and retention processing. */
    public EventBuilder<T, R, S> significantEvent() {
      this.significantEvent = true;
      return this;
    }

    // Do not inherit role permissions from states.
    public EventBuilder<T, R, S> explicitGrants() {
      this.explicitGrants = true;
      return this;
    }

    public EventBuilder<T, R, S> grantHistoryOnly(R... roles) {
      for (R role : roles) {
        historyOnlyRoles.add(role.getRole());
      }
      grant(Set.of(Permission.R), roles);

      return this;
    }

    public EventBuilder<T, R, S> grant(Permission permission, R... roles) {
      return grant(Set.of(permission), roles);
    }

    public EventBuilder<T, R, S> grant(Set<Permission> crud, R... roles) {
      for (R role : roles) {
        grants.putAll(role, crud);
      }

      return this;
    }

    public EventBuilder<T, R, S> grant(HasAccessControl... accessControls) {
      for (HasAccessControl accessControl : accessControls) {
        for (var entry : accessControl.getGrants().entries()) {
          grants.put((R) entry.getKey(), entry.getValue());
        }
      }

      return this;
    }

    public EventBuilder<T, R, S> retries(int... retries) {
      for (Webhook value : Webhook.values()) {
        setRetries(value, retries);
      }

      return this;
    }

    public EventBuilder<T, R, S> retries(Webhook hook, String retries) {
      this.retries.put(hook, retries);
      return this;
    }

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised
      // events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      ensureNoExternalCallback(Webhook.Submitted);
      this.submittedCallback = submittedCallback;
      return this;
    }

    public EventBuilder<T, R, S> aboutToStartCallback(AboutToStart<T, S> aboutToStartCallback) {
      ensureNoExternalCallback(Webhook.AboutToStart);
      this.aboutToStartCallback = aboutToStartCallback;
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised
      // events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      ensureNoExternalCallback(Webhook.AboutToSubmit);
      this.aboutToSubmitCallback = aboutToSubmitCallback;
      return this;
    }

    /**
     * Writes an existing external callback URL into the CCD definition without registering a Java
     * callback handler.
     */
    public EventBuilder<T, R, S> externalCallbackUrl(Webhook webhook, String url) {
      if (webhook == Webhook.MidEvent) {
        throw new IllegalArgumentException("Mid-event callback URLs belong to an event page");
      }
      ensureNoCallbackHandler(webhook);
      this.callbackUrls.put(webhook, url);
      return this;
    }

    private void ensureNoExternalCallback(Webhook webhook) {
      if (this.callbackUrls.containsKey(webhook)) {
        throw new IllegalStateException(
            "Cannot set both an external URL and a Java handler for " + webhook);
      }
    }

    private void ensureNoCallbackHandler(Webhook webhook) {
      boolean configured =
          switch (webhook) {
            case AboutToStart -> this.aboutToStartCallback != null || this.startHandler != null;
            case AboutToSubmit -> this.aboutToSubmitCallback != null || this.submitHandler != null;
            case Submitted -> this.submittedCallback != null || this.submitHandler != null;
            case MidEvent -> false;
          };
      if (configured) {
        throw new IllegalStateException(
            "Cannot set both an external URL and a Java handler for " + webhook);
      }
    }

    // Hide lombok's generated builder methods for these fields to stop them polluting the public
    // API.
    private void id(String value) {
      this.id = value;
    }

    private void preState(Set<S> value) {
      this.preState = value;
    }

    private void postState(Set<S> value) {
      this.postState = value;
    }

    private void dataClass(Class value) {
      this.dataClass = value;
    }

    private void grants(SetMultimap<R, Permission> value) {
      this.grants = value;
    }

    private void historyOnlyRoles(Set<String> value) {
      this.historyOnlyRoles = value;
    }

    private void setRetries(Webhook hook, int... retries) {
      if (retries.length > 0) {
        String val =
            String.join(
                ",", Arrays.stream(retries).mapToObj(String::valueOf).collect(Collectors.toList()));
        this.retries.put(hook, val);
      }
    }
  }
}
