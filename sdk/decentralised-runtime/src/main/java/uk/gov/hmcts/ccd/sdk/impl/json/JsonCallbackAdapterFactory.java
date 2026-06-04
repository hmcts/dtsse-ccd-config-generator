package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
public class JsonCallbackAdapterFactory {

  private final ObjectMapper mapper;
  private final ObjectProvider<JsonCallbackRouteRegistry> routeRegistry;

  JsonCallbackAdapterFactory(ObjectMapper mapper,
                             ObjectProvider<JsonCallbackRouteRegistry> routeRegistry) {
    this.mapper = mapper;
    this.routeRegistry = routeRegistry;
  }

  public void validate(String callbackUrl) {
    routeRegistry.getObject().validate(callbackUrl);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public AboutToSubmit aboutToSubmit(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> response(callbackUrl, eventId, details, detailsBefore);
  }

  @SuppressWarnings("rawtypes")
  public Submitted submitted(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> {
      Object response = invoke(callbackUrl, eventId, details, detailsBefore);
      if (response == null) {
        return SubmittedCallbackResponse.builder().build();
      }
      JsonCallbackResponse callbackResponse = callbackResponse(response);
      return SubmittedCallbackResponse.builder()
          .confirmationHeader(callbackResponse.confirmationHeader)
          .confirmationBody(callbackResponse.confirmationBody)
          .build();
    };
  }

  private AboutToStartOrSubmitResponse response(String callbackUrl,
                                                String eventId,
                                                CaseDetails<?, ?> details,
                                                CaseDetails<?, ?> detailsBefore) {
    Object response = invoke(callbackUrl, eventId, details, detailsBefore);
    if (response == null) {
      throw new IllegalStateException("JSON callback " + callbackUrl + " returned no response");
    }
    JsonCallbackResponse callbackResponse = callbackResponse(response);
    Object data = callbackResponse.data == null
        ? details.getData()
        : mapper.convertValue(callbackResponse.data, dataClass(details));
    return AboutToStartOrSubmitResponse.builder()
        .data(data)
        .errors(callbackResponse.errors())
        .warnings(callbackResponse.warnings())
        .state(callbackResponse.state)
        .securityClassification(callbackResponse.securityClassification)
        .build();
  }

  private Object invoke(String callbackUrl,
                        String eventId,
                        CaseDetails<?, ?> details,
                        CaseDetails<?, ?> detailsBefore) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("case_details", toCcdCaseDetails(details));
    payload.put("case_details_before", detailsBefore == null ? null : toCcdCaseDetails(detailsBefore));
    payload.put("event_id", eventId);

    return routeRegistry.getObject().invoke(callbackUrl, payload);
  }

  private Map<String, Object> toCcdCaseDetails(CaseDetails<?, ?> details) {
    JsonNode node = mapper.valueToTree(details);
    Map<String, Object> callbackDetails = mapper.convertValue(
        node,
        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
    );
    Object caseData = details.getData() == null ? Map.of() : mapper.convertValue(details.getData(), Object.class);
    callbackDetails.put("data", caseData);
    callbackDetails.put("case_data", caseData);
    return callbackDetails;
  }

  private Class<?> dataClass(CaseDetails<?, ?> details) {
    return details.getData() == null ? Map.class : details.getData().getClass();
  }

  private JsonCallbackResponse callbackResponse(Object response) {
    return response == null ? new JsonCallbackResponse() : mapper.convertValue(response, JsonCallbackResponse.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class JsonCallbackResponse {

    @JsonAlias("case_data")
    public Object data;

    public List<String> errors = List.of();

    public List<String> warnings = List.of();

    public Object state;

    @JsonProperty("security_classification")
    @JsonAlias("securityClassification")
    public String securityClassification;

    @JsonProperty("confirmation_header")
    @JsonAlias("confirmationHeader")
    public String confirmationHeader;

    @JsonProperty("confirmation_body")
    @JsonAlias("confirmationBody")
    public String confirmationBody;

    private List<String> errors() {
      return errors == null ? List.of() : errors;
    }

    private List<String> warnings() {
      return warnings == null ? List.of() : warnings;
    }
  }
}
