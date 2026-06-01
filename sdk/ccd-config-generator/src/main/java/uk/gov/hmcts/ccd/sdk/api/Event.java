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
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;
import uk.gov.hmcts.ccd.sdk.runtime.RuntimeCallback;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

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
  private RuntimeCallback<AboutToStartOrSubmitResponse> runtimeAboutToStartCallback;
  private RuntimeCallback<AboutToStartOrSubmitResponse> runtimeAboutToSubmitCallback;
  private RuntimeCallback<SubmittedCallbackResponse> runtimeSubmittedCallback;
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

    public EventBuilder<T, R, S> retries(int... retries) {
      for (Webhook value : Webhook.values()) {
        setRetries(value, retries);
      }

      return this;
    }

    public EventBuilder<T, R, S> aboutToStartCallback(AboutToStart<T, S> aboutToStartCallback) {
      if (this.startHandler == null) {
        this.runtimeAboutToStartCallback = aboutToStartCallback == null
            ? null
            : (request, context) -> aboutToStartCallback.handle(caseDetails(request, context));
      }
      return this;
    }

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      this.runtimeSubmittedCallback = submittedCallback == null
          ? null
          : (request, context) -> submittedCallback.handle(
              caseDetails(request, context),
              caseDetailsBefore(request, context)
          );
      return this;
    }


    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      this.runtimeAboutToSubmitCallback = aboutToSubmitCallback == null
          ? null
          : (request, context) -> aboutToSubmitCallback.handle(
              caseDetails(request, context),
              caseDetailsBefore(request, context)
          );
      return this;
    }

    public EventBuilder<T, R, S> submitHandler(Submit<T, S> submitHandler) {
      if (submitHandler != null && runtimeSubmittedCallback != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      if (submitHandler != null && runtimeAboutToSubmitCallback != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      this.submitHandler = submitHandler;
      return this;
    }

    public EventBuilder<T, R, S> startHandler(Start<T, S> startHandler) {
      this.startHandler = startHandler;
      if (startHandler != null) {
        this.runtimeAboutToStartCallback = (request, context) -> AboutToStartOrSubmitResponse.builder()
            .data(startHandler.start(startPayload(request, context)))
            .build();
      }
      return this;
    }

    @SuppressWarnings("unchecked")
    private CaseDetails<T, S> caseDetails(CallbackRequest request, RuntimeCallback.Context context) {
      return (CaseDetails<T, S>) context.convertCaseDetails(request.getCaseDetails());
    }

    @SuppressWarnings("unchecked")
    private CaseDetails<T, S> caseDetailsBefore(CallbackRequest request, RuntimeCallback.Context context) {
      return (CaseDetails<T, S>) context.convertCaseDetails(
          request.getCaseDetailsBefore(),
          request.getCaseDetails().getCaseTypeId()
      );
    }

    @SuppressWarnings("unchecked")
    private EventPayload<T, S> startPayload(CallbackRequest request, RuntimeCallback.Context context) {
      return (EventPayload<T, S>) context.startPayload(request);
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

    private void runtimeAboutToStartCallback(RuntimeCallback<AboutToStartOrSubmitResponse> value) {
      this.runtimeAboutToStartCallback = value;
    }

    private void runtimeAboutToSubmitCallback(RuntimeCallback<AboutToStartOrSubmitResponse> value) {
      this.runtimeAboutToSubmitCallback = value;
    }

    private void runtimeSubmittedCallback(RuntimeCallback<SubmittedCallbackResponse> value) {
      this.runtimeSubmittedCallback = value;
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
