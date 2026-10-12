package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;
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
  private String description;
  private String showCondition;
  private Map<Webhook, String> retries;
  private boolean explicitGrants;
  private boolean showSummary;
  private boolean showEventNotes;
  private boolean significant;
  private boolean canSaveDraft;
  private boolean publishToCamunda;
  // Omit the PostConditionState column entirely rather than emitting the resolved post-state; see
  // EventBuilder.postStateFromCallback().
  private boolean postStateFromCallback;
  private Integer ttlIncrement;
  private AboutToStart<T, S> aboutToStartCallback;
  private AboutToSubmit<T, S> aboutToSubmitCallback;
  private Submitted<T, S> submittedCallback;
  // Callbacks served at the service's own endpoints rather than by an SDK handler.
  @Setter(AccessLevel.NONE)
  private Map<Webhook, String> callbackUrls;
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
  @Builder.Default
  private int displayOrder = -1;

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
        String id, Class dataClass, PropertyUtils propertyUtils,
        Set<S> preStates, Set<S> postStates) {
      EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
      result.id(id);
      result.preState = preStates;
      result.postState = postStates;
      result.dataClass = dataClass;
      result.grants = HashMultimap.create();
      result.historyOnlyRoles = new HashSet<>();
      result.fieldsBuilder = FieldCollection.FieldCollectionBuilder
          .builder(result, result, dataClass, propertyUtils);
      result.retries = new HashMap<>();
      result.callbackUrls = new HashMap<>();

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

    public EventBuilder<T, R, S> showSummary(boolean show) {
      this.showSummary = show;
      return this;
    }

    public EventBuilder<T, R, S> showSummary() {
      this.showSummary = true;
      return this;
    }

    /**
     * Sets the CaseEvent sheet's {@code SignificantEvent} flag to {@code Y}. Not consumed by the
     * definition-store importer or data-store at runtime; a definition-time marker some services
     * use to flag events of note in their own tooling.
     */
    public EventBuilder<T, R, S> significant() {
      this.significant = true;
      return this;
    }

    /**
     * Sets the CaseEvent sheet's {@code CanSaveDraft} flag to {@code Y}, allowing the caseworker
     * to save a partially-completed submission and resume it later. The definition-store importer
     * rejects this on any event with a pre-state ({@code EventEntityCanSaveDraftValidatorImpl}):
     * it is only valid on create events.
     */
    public EventBuilder<T, R, S> canSaveDraft() {
      this.canSaveDraft = true;
      return this;
    }

    /**
     * Omit the {@code PostConditionState} column from this event's CaseEvent row, so the state the
     * case ends in is decided at runtime by the about-to-submit callback rather than by the
     * definition.
     *
     * <p>The three column values are three distinct runtime behaviours, and the builder's
     * state-targeting methods can only express two of them: a concrete state pins the transition,
     * {@code *} keeps the case in its current state, and an <em>absent</em> column means neither —
     * the data store finds no post-state to apply
     * ({@code CasePostStateEvaluationService.getDefaultPostStateReference} throws "No default post
     * state exists" if it is ever consulted), so the only state that lands is the one the
     * about-to-submit callback returned. Without this the generator always writes a value, forcing
     * a definition-declared transition onto an event whose hand-written definition deliberately had
     * none.
     *
     * <p>The pre-states still come from the builder as usual, so declare the transition target you
     * would otherwise want ({@code forStateTransition}) or simply {@code forState(...)} — only the
     * emitted column is suppressed. The event must have an about-to-submit callback that returns a
     * state; otherwise the case never leaves its current state.
     */
    public EventBuilder<T, R, S> postStateFromCallback() {
      this.postStateFromCallback = true;
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

    public EventBuilder<T, R, S> ttlIncrement(Integer ttlIncrement) {
      this.ttlIncrement = ttlIncrement;
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

    /**
     * Sets the retry timeouts of every callback hook. They are written to the definition, but CCD's
     * data store reads them only to see whether they are a single {@code 0}, which turns retries off
     * for that hook. Any other value leaves its default: three attempts, retried about one and three
     * seconds after the first fails.
     */
    public EventBuilder<T, R, S> retries(int... retries) {
      for (Webhook value : Webhook.values()) {
        setRetries(value, retries);
      }

      return this;
    }

    /**
     * Sets one hook's retry timeouts, as a comma-separated list. See {@link #retries(int...)}.
     */
    public EventBuilder<T, R, S> retries(Webhook hook, String retries) {
      this.retries.put(hook, retries);
      return this;
    }

    public EventBuilder<T, R, S> aboutToStartCallback(AboutToStart<T, S> aboutToStartCallback) {
      rejectCallbackUrl(Webhook.AboutToStart, aboutToStartCallback != null);
      this.aboutToStartCallback = aboutToStartCallback;
      return this;
    }

    /** Sets the about-to-start handler with its retry timeouts. */
    public EventBuilder<T, R, S> aboutToStartCallback(AboutToStart<T, S> aboutToStartCallback, int... retries) {
      aboutToStartCallback(aboutToStartCallback);
      setRetries(Webhook.AboutToStart, retries);
      return this;
    }

    /**
     * Points the about-to-start callback at an endpoint the service already serves. The URL is
     * written verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> aboutToStartCallback(String url, int... retries) {
      return callbackUrl(Webhook.AboutToStart, aboutToStartCallback != null || startHandler != null, url, retries);
    }

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      rejectCallbackUrl(Webhook.Submitted, submittedCallback != null);
      this.submittedCallback = submittedCallback;
      return this;
    }

    /** Sets the submitted handler with its retry timeouts. */
    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback, int... retries) {
      submittedCallback(submittedCallback);
      setRetries(Webhook.Submitted, retries);
      return this;
    }

    /**
     * Points the submitted callback at an endpoint the service already serves. The URL is written
     * verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> submittedCallback(String url, int... retries) {
      return callbackUrl(Webhook.Submitted, submittedCallback != null || submitHandler != null, url, retries);
    }

    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      rejectCallbackUrl(Webhook.AboutToSubmit, aboutToSubmitCallback != null);
      this.aboutToSubmitCallback = aboutToSubmitCallback;
      return this;
    }

    /** Sets the about-to-submit handler with its retry timeouts. */
    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback, int... retries) {
      aboutToSubmitCallback(aboutToSubmitCallback);
      setRetries(Webhook.AboutToSubmit, retries);
      return this;
    }

    /**
     * Points the about-to-submit callback at an endpoint the service already serves. The URL is
     * written verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> aboutToSubmitCallback(String url, int... retries) {
      return callbackUrl(Webhook.AboutToSubmit, aboutToSubmitCallback != null || submitHandler != null, url, retries);
    }

    /** Sets a decentralised event's submit handler. */
    public EventBuilder<T, R, S> submitHandler(Submit<T, S> submitHandler) {
      rejectCallbackUrl(Webhook.AboutToSubmit, submitHandler != null);
      rejectCallbackUrl(Webhook.Submitted, submitHandler != null);
      this.submitHandler = submitHandler;
      return this;
    }

    /** Sets a decentralised event's start handler, which returns the case. */
    public EventBuilder<T, R, S> startHandler(Start<T, S> startHandler) {
      rejectCallbackUrl(Webhook.AboutToStart, startHandler != null);
      this.startHandler = startHandler;
      return this;
    }

    private EventBuilder<T, R, S> callbackUrl(Webhook hook, boolean hasHandler, String url, int... retries) {
      if (hasHandler) {
        throw bothHandlerAndUrl(id, hook.toString());
      }
      this.callbackUrls.put(hook, url);
      setRetries(hook, retries);
      return this;
    }

    private void rejectCallbackUrl(Webhook hook, boolean settingHandler) {
      if (settingHandler && callbackUrls.containsKey(hook)) {
        throw bothHandlerAndUrl(id, hook.toString());
      }
    }

    static IllegalStateException bothHandlerAndUrl(String eventId, String hook) {
      return new IllegalStateException(
          "Event '%s' has both a handler and a callback URL for %s".formatted(eventId, hook));
    }

    String eventId() {
      return id;
    }

    // Hide lombok's generated builder methods for these fields to stop them polluting the public API.
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

    private void callbackUrls(Map<Webhook, String> value) {
      this.callbackUrls = value;
    }

    private void setRetries(Webhook hook, int... retries) {
      if (retries.length > 0) {
        this.retries.put(hook, joinRetries(retries));
      }
    }

    static String joinRetries(int... retries) {
      return Arrays.stream(retries).mapToObj(String::valueOf).collect(Collectors.joining(","));
    }
  }
}
