package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;
import uk.gov.hmcts.ccd.sdk.api.external.ClientContext;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalRejection;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalRejectionException;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartHandler;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartRequest;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitHandler;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitRequest;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalUser;

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
  private Integer ttlIncrement;
  private AboutToStart<T, S> aboutToStartCallback;
  private AboutToSubmit<T, S> aboutToSubmitCallback;
  private Submitted<T, S> submittedCallback;
  // Callbacks served at the service's own endpoints rather than by an SDK handler.
  @Setter(AccessLevel.NONE)
  private Map<Webhook, CallbackUrl> callbackUrls;
  // One handler per phase. A decentralised event's handlers are adapted into these; an external
  // event also has a payload type, so the runtime has a single path and branches only on that.
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private SubmitSlot<T, S> onSubmit;
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private StartSlot<T, S> onStart;
  // An external event's payload types: what its frontend submits, and what it is sent on start.
  // Null for other events; the start type is also null for an external event that sends nothing.
  @Setter(AccessLevel.NONE)
  private Class<?> submitType;
  @Setter(AccessLevel.NONE)
  private Class<?> startType;
  private FieldCollection fields;
  private boolean concurrent;

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

  /** True for decentralised events, whose submit handler replaces the callback lifecycle. */
  public boolean hasSubmitHandler() {
    return onSubmit != null;
  }

  public boolean hasStartHandler() {
    return onStart != null;
  }

  /** True for external events, which a frontend drives with a payload instead of case data. */
  public boolean isExternal() {
    return submitType != null;
  }

  /**
   * Runs the start handler: the case for a decentralised event, an
   * {@link uk.gov.hmcts.ccd.sdk.api.external.ExternalStartResponse} for an external one.
   */
  public Object start(EventPayload<T, S> event, ExternalUser user, ClientContext clientContext) {
    return onStart.apply(event, user, clientContext);
  }

  /** The start phase's handler, taking what an external event's handler needs as well as the case. */
  @FunctionalInterface
  private interface StartSlot<T, S> {
    Object apply(EventPayload<T, S> event, ExternalUser user, ClientContext clientContext);
  }

  /**
   * Runs the submit handler. The frontend's payload and the user who sent it are an external
   * event's; both are null for a decentralised event.
   */
  public SubmitResponse<S> submit(EventPayload<T, S> event, Object payload, ExternalUser user) {
    return onSubmit.apply(event, payload, user);
  }

  /** The submit phase's handler, taking what an external event's handler needs as well as the case. */
  @FunctionalInterface
  private interface SubmitSlot<T, S> {
    SubmitResponse<S> apply(EventPayload<T, S> event, Object payload, ExternalUser user);
  }

  /** A decentralised event's start handler, for code that calls it directly; null for an external event. */
  @SuppressWarnings("unchecked")
  public Start<T, S> getStartHandler() {
    return onStart == null || isExternal() ? null : event -> (T) onStart.apply(event, null, ClientContext.none());
  }

  /** A decentralised event's submit handler, for code that calls it directly; null for an external event. */
  public Submit<T, S> getSubmitHandler() {
    return onSubmit == null || isExternal() ? null : event -> onSubmit.apply(event, null, null);
  }

  public static class EventBuilder<T, R extends HasRole, S> {

    private FieldCollection.FieldCollectionBuilder<T, S, EventBuilder<T, R, S>> fieldsBuilder;
    private boolean concurrent = true;

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

    /**
     * Makes this an external event. Called by the SDK's external event builder; declare external
     * events with {@code DecentralisedConfigBuilder.externalEvent}.
     */
    @SuppressWarnings("unchecked")
    public <I> EventBuilder<T, R, S> external(ExternalEventId<?, I> id, ExternalSubmitHandler<S, I> submit) {
      rejectCallbackUrl(Webhook.AboutToSubmit, true);
      rejectCallbackUrl(Webhook.Submitted, true);
      // Immutable, so the event's roles keep create and read on it even under explicitGrants().
      fieldsBuilder.field(DecentralisedConfigBuilder.PAYLOAD_FIELD).type("TextArea").optional().immutable();
      this.submitType = id.submitType();
      this.startType = id.startType();
      this.onSubmit = (event, payload, user) -> toSubmitResponse(rejectingOnThrow(() ->
          // The runtime has already read the payload as submitType; a cast through the class would
          // reject a boxed value for a primitive payload type.
          submit.submit(new ExternalSubmitRequest<>(event.caseReference(), (I) payload, user))));
      return this;
    }

    /** Sets an external event's start handler. Called by the SDK's external event builder. */
    public EventBuilder<T, R, S> externalStartHandler(ExternalStartHandler<?> start) {
      rejectCallbackUrl(Webhook.AboutToStart, true);
      this.onStart = (event, user, clientContext) -> rejectingOnThrow(() ->
          start.start(new ExternalStartRequest(event.caseReference(), user, clientContext)));
      return this;
    }

    /** A handler may throw its rejection from deeper code rather than return it; both mean the same. */
    @SuppressWarnings("unchecked")
    private static <R> R rejectingOnThrow(Supplier<R> handler) {
      try {
        return handler.get();
      } catch (ExternalRejectionException rejection) {
        return (R) new ExternalRejection<>(rejection.errors());
      }
    }

    private static <S> SubmitResponse<S> toSubmitResponse(ExternalSubmitResponse<S> response) {
      return switch (response) {
        case ExternalSubmitResponse.Accepted<S> accepted -> SubmitResponse.<S>builder()
            .state(accepted.state())
            .eventMetadata(accepted.summary() == null && accepted.description() == null ? null
                : EventMetadata.builder().summary(accepted.summary()).description(accepted.description()).build())
            .build();
        case ExternalRejection<S> rejected -> SubmitResponse.<S>builder()
            .errors(rejected.errors())
            .build();
      };
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

    /**
     * Rejects submission with HTTP 409 if any event committed on the case after this one started.
     * Use when the submit handler writes values taken from the event payload rather than a fresh read.
     * Has no effect on case-creation events, which have no start revision.
     */
    public EventBuilder<T, R, S> nonConcurrent() {
      concurrent = false;
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

    public EventBuilder<T, R, S> aboutToStartCallback(AboutToStart<T, S> aboutToStartCallback) {
      rejectCallbackUrl(Webhook.AboutToStart, aboutToStartCallback != null);
      this.aboutToStartCallback = aboutToStartCallback;
      return this;
    }

    /**
     * Points the about-to-start callback at an endpoint the service already serves. The URL is
     * written verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> aboutToStartCallback(String url, int... retries) {
      return callbackUrl(Webhook.AboutToStart, aboutToStartCallback != null || onStart != null, url, retries);
    }

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.onSubmit != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      rejectCallbackUrl(Webhook.Submitted, submittedCallback != null);
      this.submittedCallback = submittedCallback;
      return this;
    }

    /**
     * Points the submitted callback at an endpoint the service already serves. The URL is written
     * verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> submittedCallback(String url, int... retries) {
      return callbackUrl(Webhook.Submitted, submittedCallback != null || onSubmit != null, url, retries);
    }

    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.onSubmit != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      rejectCallbackUrl(Webhook.AboutToSubmit, aboutToSubmitCallback != null);
      this.aboutToSubmitCallback = aboutToSubmitCallback;
      return this;
    }

    /**
     * Points the about-to-submit callback at an endpoint the service already serves. The URL is
     * written verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public EventBuilder<T, R, S> aboutToSubmitCallback(String url, int... retries) {
      return callbackUrl(Webhook.AboutToSubmit, aboutToSubmitCallback != null || onSubmit != null, url, retries);
    }

    private EventBuilder<T, R, S> callbackUrl(Webhook hook, boolean hasHandler, String url, int... retries) {
      if (hasHandler) {
        throw bothHandlerAndUrl(id, hook.toString());
      }
      this.callbackUrls.put(hook, CallbackUrl.of(url, retries));
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
    // An external event's handlers are set through external(...) and externalStartHandler(...).
    private void submitType(Class<?> value) {
      this.submitType = value;
    }

    private void startType(Class<?> value) {
      this.startType = value;
    }

    private void onSubmit(SubmitSlot<T, S> value) {
      this.onSubmit = value;
    }

    private void onStart(StartSlot<T, S> value) {
      this.onStart = value;
    }

    private void callbackUrls(Map<Webhook, CallbackUrl> value) {
      this.callbackUrls = value;
    }

    /** Sets a decentralised event's submit handler. */
    public EventBuilder<T, R, S> submitHandler(Submit<T, S> handler) {
      rejectCallbackUrl(Webhook.AboutToSubmit, handler != null);
      rejectCallbackUrl(Webhook.Submitted, handler != null);
      this.onSubmit = handler == null ? null : (event, payload, user) -> handler.submit(event);
      return this;
    }

    /** Sets a decentralised event's start handler, which returns the case. */
    public EventBuilder<T, R, S> startHandler(Start<T, S> handler) {
      rejectCallbackUrl(Webhook.AboutToStart, handler != null);
      this.onStart = handler == null ? null : (event, user, clientContext) -> handler.start(event);
      return this;
    }

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
        String val = String.join(",", Arrays.stream(retries).mapToObj(String::valueOf).collect(
            Collectors.toList()));
        this.retries.put(hook, val);
      }
    }
  }
}
