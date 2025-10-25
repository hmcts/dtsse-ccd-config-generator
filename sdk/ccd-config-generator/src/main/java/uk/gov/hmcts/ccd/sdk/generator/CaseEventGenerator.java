package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.generator.GeneratorUtils.hasAnyDisplayOrder;
import static uk.gov.hmcts.ccd.sdk.generator.GeneratorUtils.sortDisplayOrderByEventName;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class CaseEventGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {

    File folder = new File(root.getPath(), "CaseEvent");
    folder.mkdir();

    List<Event<T, R, S>> events = getOrderedEvents(config.getEvents().values());

    for (Event event : events) {
      Path output = Paths.get(folder.getPath(), event.getId() + ".json");

      JsonUtils.mergeInto(output, serialise(config.getCaseType(), event, config.getAllStates(),
              config.getCallbackHost()),
          new AddMissing(), "ID");
    }
  }

  private List<Event<T, R, S>> getOrderedEvents(ImmutableCollection<Event<T, R, S>> originalEvents) {
    if (hasAnyDisplayOrder(originalEvents)) {
      return new ArrayList<>(originalEvents);
    }
    return sortDisplayOrderByEventName(originalEvents);
  }

  private List<Map<String, Object>> serialise(String caseTypeId, Event<T, R, S> event,
                                              Set<S> allStates, String callbackHost) {
    List<Map<String, Object>> result = Lists.newArrayList();
    Map<String, Object> data = JsonUtils.getField(event.getId());
    result.add(data);
    data.put("Name", event.getName());
    data.put("Description", event.getDescription());
    data.put("DisplayOrder", resolveDisplayOrder(event));
    data.put("CaseTypeID", caseTypeId);
    JsonUtils.putYn(data, "ShowSummary", event.isShowSummary());
    JsonUtils.putYn(data, "ShowEventNotes", event.isShowEventNotes());
    JsonUtils.putYn(data, "Publish", event.isPublishToCamunda());
    if (!Strings.isNullOrEmpty(event.getEndButtonLabel())) {
      data.put("EndButtonLabel", event.getEndButtonLabel());
    }
    if (Objects.nonNull(event.getTtlIncrement())) {
      data.put("TTLIncrement", event.getTtlIncrement());
    }

    if (!Strings.isNullOrEmpty(event.getShowCondition())) {
      data.put("EventEnablingCondition", event.getShowCondition());
    }

    if (!event.getPreState().isEmpty()) {
      data.put("PreConditionState(s)", getPreStateString(event.getPreState(), allStates));
    }

    data.put("PostConditionState", getPostStateString(event.getPostState()));
    data.put("SecurityClassification", "Public");

    addCallbackIfConfigured(data, callbackHost, event,
        event.getAboutToStartCallback() != null || event.getStartHandler() != null,
        CallbackMetadata.ABOUT_TO_START);
    addCallbackIfConfigured(data, callbackHost, event,
        event.getAboutToSubmitCallback() != null,
        CallbackMetadata.ABOUT_TO_SUBMIT);
    addCallbackIfConfigured(data, callbackHost, event,
        event.getSubmittedCallback() != null,
        CallbackMetadata.SUBMITTED);

    return result;
  }

  private String getPreStateString(Set<S> states, Set<S> allStates) {
    return states.equals(allStates)
        ? "*"
        : states.stream().map(Objects::toString)
        .sorted()
        .collect(Collectors.joining(";"));
  }

  private String getPostStateString(Set<S> states) {
    return states.size() != 1
        ? "*"
        : states.stream().findFirst().map(Objects::toString).orElse("");
  }

  private int resolveDisplayOrder(Event<T, R, S> event) {
    return event.getDisplayOrder() != -1 ? event.getDisplayOrder() : 1;
  }

  private void addCallbackIfConfigured(Map<String, Object> target,
                                       String callbackHost,
                                       Event<T, R, S> event,
                                       boolean enabled,
                                       CallbackMetadata metadata) {
    if (!enabled) {
      return;
    }
    target.put(metadata.callbackField(),
        metadata.buildUrl(callbackHost, event.getId()));
    String retry = event.getRetries().get(metadata.webhook());
    if (retry != null) {
      target.put(metadata.retriesField(), retry);
    }
  }

  private record CallbackMetadata(Webhook webhook,
                                  String callbackField,
                                  String retriesField,
                                  Function<String, String> pathBuilder) {

    private static final CallbackMetadata ABOUT_TO_START = new CallbackMetadata(
        Webhook.AboutToStart,
        "CallBackURLAboutToStartEvent",
        "RetriesTimeoutURLAboutToStartEvent",
        eventId -> "/callbacks/about-to-start?eventId=" + eventId
    );

    private static final CallbackMetadata ABOUT_TO_SUBMIT = new CallbackMetadata(
        Webhook.AboutToSubmit,
        "CallBackURLAboutToSubmitEvent",
        "RetriesTimeoutURLAboutToSubmitEvent",
        eventId -> "/callbacks/about-to-submit?eventId=" + eventId
    );

    private static final CallbackMetadata SUBMITTED = new CallbackMetadata(
        Webhook.Submitted,
        "CallBackURLSubmittedEvent",
        "RetriesTimeoutURLSubmittedEvent",
        eventId -> "/callbacks/submitted?eventId=" + eventId
    );

    String buildUrl(String callbackHost, String eventId) {
      return callbackHost + pathBuilder.apply(eventId);
    }
  }
}
