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
  private String description;
  private String showCondition;
  private Map<Webhook, String> retries;
  private Map<Webhook, String> callbackUrls;
  private Map<String, Object> caseEventColumns;
  private boolean explicitGrants;
  private boolean showSummary;
  private boolean showEventNotes;
  private boolean publishToCamunda;
  private boolean omitPublish;
  private boolean omitLiveFrom;
  private String significantEvent;
  private Integer ttlIncrement;
  private String ttlIncrementRaw;
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


  private String endButtonLabel;
  @Builder.Default
  private int displayOrder = -1;

  private SetMultimap<R, Permission> grants;
  private Map<R, Map<String, Object>> authorisationCaseEventColumns;
  private Set<String> historyOnlyRoles;

  public Set<String> getHistoryOnlyRoles() {
    return historyOnlyRoles;
  }

  private Class dataClass;
  private static int eventCount;

  public static class EventBuilder<T, R extends HasRole, S> {

    private FieldCollection.FieldCollectionBuilder<T, S, EventBuilder<T, R, S>> fieldsBuilder;
    private boolean omitLiveFromSet;
    private boolean omitPublishSet;
    private boolean endButtonLabelSet;

    public static <T, R extends HasRole, S> EventBuilder<T, R, S> builder(
        String id, Class dataClass, PropertyUtils propertyUtils,
        Set<S> preStates, Set<S> postStates) {
      EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
      result.id(id);
      result.preState = preStates;
      result.postState = postStates;
      result.dataClass = dataClass;
      result.grants = HashMultimap.create();
      result.authorisationCaseEventColumns = new HashMap<>();
      result.historyOnlyRoles = new HashSet<>();
      result.fieldsBuilder = FieldCollection.FieldCollectionBuilder
          .builder(result, result, dataClass, propertyUtils);
      result.retries = new HashMap<>();
      result.callbackUrls = new HashMap<>();
      result.caseEventColumns = new HashMap<>();
      result.endButtonLabel = "Save and continue";

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
      this.omitPublish = false;
      this.omitPublishSet = true;
      return this;
    }

    public EventBuilder<T, R, S> publishToCamunda() {
      this.publishToCamunda = true;
      this.omitPublish = false;
      this.omitPublishSet = true;
      return this;
    }

    public EventBuilder<T, R, S> omitPublish() {
      this.omitPublish = true;
      this.omitPublishSet = true;
      return this;
    }

    public EventBuilder<T, R, S> omitLiveFrom() {
      this.omitLiveFrom = true;
      this.omitLiveFromSet = true;
      return this;
    }

    public EventBuilder<T, R, S> includeLiveFrom() {
      this.omitLiveFrom = false;
      this.omitLiveFromSet = true;
      return this;
    }

    public EventBuilder<T, R, S> noEndButtonLabel() {
      return endButtonLabel("");
    }

    public EventBuilder<T, R, S> endButtonLabel(String label) {
      this.endButtonLabel = label;
      this.endButtonLabelSet = true;
      return this;
    }

    public EventBuilder<T, R, S> significantEvent() {
      return significantEvent("Yes");
    }

    public EventBuilder<T, R, S> significantEvent(String value) {
      this.significantEvent = value;
      return this;
    }

    public EventBuilder<T, R, S> ttlIncrement(Integer ttlIncrement) {
      this.ttlIncrement = ttlIncrement;
      return this;
    }

    public EventBuilder<T, R, S> ttlIncrement(String ttlIncrement) {
      this.ttlIncrementRaw = ttlIncrement;
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

    public EventBuilder<T, R, S> authorisationCaseEventColumn(R role, String column, Object value) {
      authorisationCaseEventColumns.computeIfAbsent(role, ignored -> new HashMap<>()).put(column, value);
      return this;
    }

    public EventBuilder<T, R, S> retries(int... retries) {
      for (Webhook value : Webhook.values()) {
        setRetries(value, retries);
      }

      return this;
    }

    public EventBuilder<T, R, S> aboutToStartCallbackUrl(String url) {
      this.callbackUrls.put(Webhook.AboutToStart, url);
      return this;
    }

    public EventBuilder<T, R, S> aboutToSubmitCallbackUrl(String url) {
      this.callbackUrls.put(Webhook.AboutToSubmit, url);
      return this;
    }

    public EventBuilder<T, R, S> submittedCallbackUrl(String url) {
      this.callbackUrls.put(Webhook.Submitted, url);
      return this;
    }

    public EventBuilder<T, R, S> blankCallbackUrls() {
      return aboutToStartCallbackUrl("")
          .aboutToSubmitCallbackUrl("")
          .submittedCallbackUrl("");
    }

    public EventBuilder<T, R, S> caseEventColumn(String column, Object value) {
      this.caseEventColumns.put(column, value);
      return this;
    }

    public EventBuilder<T, R, S> applyDefaults(EventDefaults defaults) {
      if (defaults.isOmitLiveFrom() && !omitLiveFromSet) {
        this.omitLiveFrom = true;
      }
      if (defaults.isOmitPublish() && !omitPublishSet) {
        this.omitPublish = true;
      }
      if (defaults.hasEndButtonLabel() && !endButtonLabelSet) {
        this.endButtonLabel = defaults.getEndButtonLabel();
      }
      return this;
    }

    public EventBuilder<T, R, S> submittedCallback(Submitted<T, S> submittedCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and submittedCallback");
      }
      this.submittedCallback = submittedCallback;
      return this;
    }


    public EventBuilder<T, R, S> aboutToSubmitCallback(AboutToSubmit<T, S> aboutToSubmitCallback) {
      // TODO: split out decentralised event building to remove these fields for decentralised events.
      if (this.submitHandler != null) {
        throw new IllegalStateException("Cannot set both submitHandler and aboutToSubmitCallback");
      }
      this.aboutToSubmitCallback = aboutToSubmitCallback;
      return this;
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

    private void setRetries(Webhook hook, int... retries) {
      if (retries.length > 0) {
        String val = String.join(",", Arrays.stream(retries).mapToObj(String::valueOf).collect(
            Collectors.toList()));
        this.retries.put(hook, val);
      }
    }
  }
}
