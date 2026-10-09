package uk.gov.hmcts.divorce.simplecase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callbacks the simple case type reaches by URL rather than through an SDK handler, the way a service
 * migrating from a hand-written definition keeps its existing endpoints.
 */
@RestController
@RequestMapping(UrlCallbackController.PATH)
public class UrlCallbackController {

    public static final String PATH = "/simple-case/url-callbacks";
    public static final String ABOUT_TO_SUBMIT_MARKER = "set by URL about-to-submit";
    public static final String MID_EVENT_MARKER = "set by URL mid-event";
    public static final String CONFIRMATION_HEADER = "# URL submitted callback ran";

    public static volatile int aboutToStartCalls;
    public static volatile int midEventCalls;
    public static volatile int aboutToSubmitCalls;
    public static volatile int submittedCalls;

    @PostMapping("/about-to-start")
    public Map<String, Object> aboutToStart(@RequestBody Map<String, Object> request) {
        aboutToStartCalls++;
        return response(caseData(request));
    }

    @PostMapping("/mid-event")
    public Map<String, Object> midEvent(@RequestBody Map<String, Object> request) {
        midEventCalls++;
        Map<String, Object> data = caseData(request);
        data.put("description", MID_EVENT_MARKER);
        return response(data);
    }

    @PostMapping("/about-to-submit")
    public Map<String, Object> aboutToSubmit(@RequestBody Map<String, Object> request) {
        aboutToSubmitCalls++;
        Map<String, Object> data = caseData(request);
        data.put("followUpNote", ABOUT_TO_SUBMIT_MARKER);
        return response(data);
    }

    @PostMapping("/submitted")
    public Map<String, Object> submitted(@RequestBody Map<String, Object> request) {
        submittedCalls++;
        return Map.of("confirmation_header", CONFIRMATION_HEADER);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> caseData(Map<String, Object> request) {
        Map<String, Object> caseDetails = (Map<String, Object>) request.get("case_details");
        Object data = caseDetails == null ? null : caseDetails.get("case_data");
        return data == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) data);
    }

    private static Map<String, Object> response(Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("errors", List.of());
        response.put("warnings", List.of());
        return response;
    }
}
