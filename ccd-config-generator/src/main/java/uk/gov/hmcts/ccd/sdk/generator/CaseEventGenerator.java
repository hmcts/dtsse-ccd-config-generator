package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Webhook;

public class CaseEventGenerator {

  public static void writeEvents(File root, String caseType, List<Event> events) {

    ImmutableListMultimap<String, Event> eventsByState = Multimaps.index(events, x -> {
      if (x.getPreState() == null) {
        return x.getPostState();
      }

      return Objects.equals(x.getPreState(), x.getPostState()) ? x.getPostState() : "StateChange";
    });

    File folder = new File(root.getPath(), "CaseEvent");
    folder.mkdir();
    for (String state : eventsByState.keys()) {
      Path output = Paths.get(folder.getPath(), state + ".json");

      Ordering<Event> ordering = Ordering.natural().onResultOf(x -> x.getEventNumber());
      JsonUtils.mergeInto(output, serialise(caseType,
          ordering.sortedCopy(eventsByState.get(state))),
          "ID");
    }
  }

  private static List<Map<String, Object>> serialise(String caseTypeId, List<Event> events) {
    int t = 1;
    List result = Lists.newArrayList();
    for (Event event : events) {
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

      if (event.getPreState() != null) {
        data.put("PreConditionState(s)", event.getPreState());
      }
      data.put("PostConditionState", event.getPostState());
      data.put("SecurityClassification", "Public");

      if (event.getAboutToStartURL() != null) {
        data.put("CallBackURLAboutToStartEvent", event.getAboutToStartURL());
        if (event.getRetries().containsKey(Webhook.AboutToStart)) {
          data.put("RetriesTimeoutAboutToStartEvent", event.getRetries().get(Webhook.AboutToStart));
        }
      }

      if (event.getAboutToSubmitURL() != null) {
        data.put("CallBackURLAboutToSubmitEvent", event.getAboutToSubmitURL());
        if (event.getRetries().containsKey(Webhook.AboutToSubmit)) {
          data.put("RetriesTimeoutURLAboutToSubmitEvent",
              event.getRetries().get(Webhook.AboutToSubmit));
        }
      }

      if (event.getSubmittedURL() != null) {
        data.put("CallBackURLSubmittedEvent", event.getSubmittedURL());
        if (event.getRetries().containsKey(Webhook.Submitted)) {
          data.put("RetriesTimeoutURLSubmittedEvent", event.getRetries().get(Webhook.Submitted));
        }
      }
    }

    return result;
  }
}
