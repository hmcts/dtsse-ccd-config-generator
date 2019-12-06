package ccd.sdk.generator;

import ccd.sdk.types.Event;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class EventGenerator {
    public static void writeEvents(File root, String caseType, List<Event> events) {
        ImmutableListMultimap<String, Event> eventsByState = Multimaps.index(events, x -> x.getPostState());

        File folder = new File(root.getPath(), "CaseEvent");
        folder.mkdir();
        for (String state : eventsByState.keys()) {
            Path output = Paths.get(folder.getPath(), state + ".json");

            Utils.writeFile(output, serialise(caseType, eventsByState.get(state)));
        }
        writeAuthorisationCaseEvent(root, events);
    }

    private static void writeAuthorisationCaseEvent(File root, List<Event> events) {
        List<Map<String, String>> result = Lists.newArrayList();
        for (Event event : events) {
            Map<String, String> grants = event.getGrants();
            for (String role : grants.keySet()) {
                Map<String, String> data = Maps.newHashMap();
                result.add(data);
                data.put("LiveFrom", "01/01/2017");
                data.put("CaseTypeID", "CARE_SUPERVISION_EPO");
                data.put("CaseEventID", event.getId());
                data.put("UserRole", role);
                data.put("CRUD", grants.get(role));
            }
        }

        File folder = new File(root.getPath(), "AuthorisationCaseEvent");
        folder.mkdir();
        Path output = Paths.get(folder.getPath(), "AuthorisationCaseEvent.json");

        Utils.writeFile(output, Utils.serialise(result));
    }

    private static String serialise(String caseTypeId, List<Event> events) {
        int t = 1;
        List result = Lists.newArrayList();
        for (Event event : events) {
            Map<String, Object> data = Utils.getField(event.getId());
            result.add(data);
            data.put("Name", event.getName());
            data.put("Description", event.getDescription());
            if (event.getDisplayOrder() != -1) {
                data.put("DisplayOrder", event.getDisplayOrder());
            } else {
                data.put("DisplayOrder", t++);
            }
            data.put("CaseTypeID", caseTypeId);
            data.put("ShowSummary", "N");
            data.put("ShowEventNotes", "N");
            data.put("EndButtonLabel", event.getEndButtonLabel());


            if (event.getPreState() != null) {
                data.put("PreConditionState(s)", event.getPreState());
            }
            data.put("PostConditionState", event.getPostState());
            data.put("SecurityClassification", "Public");

            if (event.getAboutToStartURL() != null) {
                data.put("CallBackURLAboutToStartEvent", formatUrl(event.getAboutToStartURL()));
                if (event.getRetries() != null) {
                    data.put("RetriesTimeoutURLAboutToStartEvent", event.getRetries());
                }
            }

            if (event.getAboutToSubmitURL() != null) {
                data.put("CallBackURLAboutToSubmitEvent", formatUrl(event.getAboutToSubmitURL()));
                if (event.getRetries() != null) {
                    data.put("RetriesTimeoutURLAboutToSubmitEvent", event.getRetries());
                }
            }

            if (event.getSubmittedURL() != null) {
                data.put("CallBackURLSubmittedEvent", formatUrl(event.getSubmittedURL()));
                if (event.getRetries() != null) {
                    data.put("RetriesTimeoutURLSubmittedEvent", event.getRetries());
                }
            }
        }

        return Utils.serialise(result);
    }

    private static String formatUrl(String url) {
        return "${CCD_DEF_CASE_SERVICE_BASE_URL}/callback" + url;
    }
}
