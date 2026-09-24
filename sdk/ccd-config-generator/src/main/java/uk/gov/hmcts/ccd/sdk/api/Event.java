package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
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
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStart;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartHandler;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmit;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitHandler;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;

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
  private boolean publishToCamunda;
  private Integer ttlIncrement;
  private AboutToStart<T, S> aboutToStartCallback;
  private AboutToSubmit<T, S> aboutToSubmitCallback;
  private Submitted<T, S> submittedCallback;
  // One handler per phase. A decentralised event's handlers are adapted into these; an external
  // event also has a payload type, so the runtime has a single path and branches only on that.
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private BiFunction<EventPayload<T, S>, Object, SubmitResponse<S>> onSubmit;
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Function<EventPayload<T, S>, Object> onStart;
  // An external event's payload types: what its frontend submits, and what it is sent on start.
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
  public Object start(EventPayload<T, S> event) {
    return onStart.apply(event);
  }

  /** Runs the submit handler with the frontend's payload, which is null for a decentralised event. */
  public SubmitResponse<S> submit(EventPayload<T, S> event, Object payload) {
    return onSubmit.apply(event, payload);
  }

  /** A decentralised event's start handler, for code that calls it directly; null for an external event. */
  @SuppressWarnings("unchecked")
  public Start<T, S> getStartHandler() {
    return onStart == null || isExternal() ? null : event -> (T) onStart.apply(event);
  }

  /** A decentralised event's submit handler, for code that calls it directly; null for an external event. */
  public Submit<T, S> getSubmitHandler() {
    return onSubmit == null || isExternal() ? null : event -> onSubmit.apply(event, null);
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
    public <I, O> EventBuilder<T, R, S> external(Class<I> submitType,
                                                 ExternalSubmitHandler<S, I> submit,
                                                 Class<O> startType,
                                                 ExternalStartHandler<O> start) {
      if (!id.startsWith(ExternalEventId.PREFIX)) {
        throw new IllegalArgumentException(
            "Event " + id + " is not external; declare external events with externalEvent(...)");
      }
      if (this.submitType == null) {
        // Immutable, so the event's roles keep create and read on it even under explicitGrants().
        fieldsBuilder.field(DecentralisedConfigBuilder.PAYLOAD_FIELD).type("TextArea").optional().immutable();
      }
      this.submitType = submitType;
      this.startType = startType;
      this.onSubmit = (event, payload) -> toSubmitResponse(
          // The runtime has already read the payload as submitType; a cast through the class would
          // reject a boxed value for a primitive payload type.
          submit.submit(new ExternalSubmit<>(event.caseReference(), (I) payload)));
      this.onStart = start == null ? null
          : event -> start.start(new ExternalStart(event.caseReference()));
      return this;
    }

    private static <S> SubmitResponse<S> toSubmitResponse(ExternalSubmitResponse<S> response) {
      return switch (response) {
        case ExternalSubmitResponse.Accepted<S> accepted -> SubmitResponse.<S>builder()
            .state(accepted.state())
            .eventMetadata(accepted.summary() == null && accepted.description() == null ? null
                : EventMetadata.builder().summary(accepted.summary()).description(accepted.description()).build())
            .build();
        case ExternalSubmitResponse.Rejected<S> rejected -> SubmitResponse.<S>builder()
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

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.onSubmit != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      this.submittedCallback = submittedCallback;
      return this;
    }


    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.onSubmit != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      this.aboutToSubmitCallback = aboutToSubmitCallback;
      return this;
    }

    // Hide lombok's generated builder methods for these fields to stop them polluting the public API.
    // The payload handlers are set through payload(...), which also registers the payload field.
    private void submitType(Class<?> value) {
      this.submitType = value;
    }

    private void startType(Class<?> value) {
      this.startType = value;
    }

    private void onSubmit(BiFunction<EventPayload<T, S>, Object, SubmitResponse<S>> value) {
      this.onSubmit = value;
    }

    private void onStart(Function<EventPayload<T, S>, Object> value) {
      this.onStart = value;
    }

    /** Sets a decentralised event's submit handler. */
    public EventBuilder<T, R, S> submitHandler(Submit<T, S> handler) {
      requireNotExternal();
      this.onSubmit = handler == null ? null : (event, ignored) -> handler.submit(event);
      return this;
    }

    /** Sets a decentralised event's start handler, which returns the case. */
    public EventBuilder<T, R, S> startHandler(Start<T, S> handler) {
      requireNotExternal();
      this.onStart = handler == null ? null : handler::start;
      return this;
    }

    private void requireNotExternal() {
      if (submitType != null) {
        throw new IllegalStateException("External event " + id + " takes its handlers from externalEvent(...)");
      }
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
