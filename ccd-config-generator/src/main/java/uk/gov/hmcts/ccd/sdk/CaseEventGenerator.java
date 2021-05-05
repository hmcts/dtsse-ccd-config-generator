package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Webhook;

class CaseEventGenerator<T, S, R extends HasRole> {

  public void writeEvents(File root, ResolvedCCDConfig<T, S, R> config) {

    File folder = new File(root.getPath(), "CaseEvent");
    folder.mkdir();
    for (Event event : config.events) {
      Path output = Paths.get(folder.getPath(), event.getEventId() + ".json");

      JsonUtils.mergeInto(output, serialise(config.builder.caseType, event, config.allStates,
          config.builder.callbackHost),
          new AddMissing(), "ID");
    }
  }

  private List<Map<String, Object>> serialise(String caseTypeId, Event<T, R, S> event,
                                              Set<S> allStates, String callbackHost) {
    int t = 1;
    List result = Lists.newArrayList();
    Map<String, Object> data = JsonUtils.getField(event.getEventId());
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
    }

    if (event.isShowEventNotes()) {
      data.put("ShowEventNotes", "Y");
    }
    if (event.isShowSummaryChangeOption()) {
      data.put("ShowSummaryChangeOption", "Y");
    }

    if (!Strings.isNullOrEmpty(event.getEndButtonLabel())) {
      data.put("EndButtonLabel", event.getEndButtonLabel());
    }

    if (!event.getPreState().isEmpty()) {
      data.put("PreConditionState(s)", toCCDStateString(event.getPreState(), allStates));
    }
    // Event must target either on or all states
    boolean isToAllStates = event.getPostState().equals(allStates);
    // Events can either target a specific state or can be overridden to all.
    assert event.getPostState().size() == 1 || isToAllStates;
    data.put("PostConditionState", toCCDStateString(event.getPostState(), allStates));
    data.put("SecurityClassification", "Public");

    if (event.getAboutToStartCallback() != null) {
      String url = callbackHost + "/callbacks/about-to-start";
      data.put("CallBackURLAboutToStartEvent", url);
      if (event.getRetries().containsKey(Webhook.AboutToStart)) {
        data.put("RetriesTimeoutURLAboutToStartEvent",
            event.getRetries().get(Webhook.AboutToStart));
      }
    }

    if (event.getAboutToSubmitCallback() != null) {
      String url = callbackHost + "/callbacks/about-to-submit";
      data.put("CallBackURLAboutToSubmitEvent", url);
      if (event.getRetries().containsKey(Webhook.AboutToSubmit)) {
        data.put("RetriesTimeoutURLAboutToSubmitEvent",
            event.getRetries().get(Webhook.AboutToSubmit));
      }
    }

    if (event.getSubmittedCallback() != null) {
      String url = callbackHost + "/callbacks/submitted";
      data.put("CallBackURLSubmittedEvent", url);
      if (event.getRetries().containsKey(Webhook.Submitted)) {
        data.put("RetriesTimeoutURLSubmittedEvent",
            event.getRetries().get(Webhook.Submitted));
      }
    }

    return result;
  }

  private String toCCDStateString(Set<S> states, Set<S> allStates) {
    return states.equals(allStates)
        ? "*"
        : states.stream().map(Objects::toString)
        .sorted()
        .collect(Collectors.joining(";"));
  }
}
