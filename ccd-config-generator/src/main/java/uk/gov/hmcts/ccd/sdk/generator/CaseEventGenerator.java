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
    int t = 1;
    List result = Lists.newArrayList();
    Map<String, Object> data = JsonUtils.getField(event.getId());
    result.add(data);
    data.put("Name", event.getName());
    data.put("Description", event.getDescription());
    if (event.getDisplayOrder() != -1) {
      data.put("DisplayOrder", event.getDisplayOrder());
    } else {
      data.put("DisplayOrder", t++);
    }
    data.put("CaseTypeID", caseTypeId);
    if (event.isShowSummary()) {
      data.put("ShowSummary", "Y");
    } else {
      data.put("ShowSummary", "N");
    }

    if (event.isShowEventNotes()) {
      data.put("ShowEventNotes", "Y");
    } else {
      data.put("ShowEventNotes", "N");
    }
    if (event.isPublishToCamunda()) {
      data.put("Publish", "Y");
    } else {
      data.put("Publish", "N");
    }
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

    if (event.getAboutToStartCallback() != null) {
      String url = callbackHost + "/callbacks/about-to-start?eventId=" + event.getId();
      data.put("CallBackURLAboutToStartEvent", url);
      if (event.getRetries().containsKey(Webhook.AboutToStart)) {
        data.put("RetriesTimeoutURLAboutToStartEvent",
            event.getRetries().get(Webhook.AboutToStart));
      }
    }

    if (event.getAboutToSubmitCallback() != null) {
      String url = callbackHost + "/callbacks/about-to-submit?eventId=" + event.getId();
      data.put("CallBackURLAboutToSubmitEvent", url);
      if (event.getRetries().containsKey(Webhook.AboutToSubmit)) {
        data.put("RetriesTimeoutURLAboutToSubmitEvent",
            event.getRetries().get(Webhook.AboutToSubmit));
      }
    }

    if (event.getSubmittedCallback() != null) {
      String url = callbackHost + "/callbacks/submitted?eventId=" + event.getId();
      data.put("CallBackURLSubmittedEvent", url);
      if (event.getRetries().containsKey(Webhook.Submitted)) {
        data.put("RetriesTimeoutURLSubmittedEvent",
            event.getRetries().get(Webhook.Submitted));
      }
    }

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

}
