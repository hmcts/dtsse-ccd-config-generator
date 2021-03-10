package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;

class CaseEventGenerator {

  public static void writeEvents(File root, String caseType, List<Event> events) {

    File folder = new File(root.getPath(), "CaseEvent");
    folder.mkdir();
    for (Event event : events) {
      Path output = Paths.get(folder.getPath(), event.getId() + ".json");

      JsonUtils.mergeInto(output, serialise(caseType, event),
          new AddMissing(), "ID");
    }
  }

  private static List<Map<String, Object>> serialise(String caseTypeId, Event event) {
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

    return result;
  }
}
