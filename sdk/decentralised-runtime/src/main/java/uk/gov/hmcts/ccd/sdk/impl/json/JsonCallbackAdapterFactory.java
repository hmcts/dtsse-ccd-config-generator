package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;
import uk.gov.hmcts.ccd.sdk.impl.CallbackInvocationContext;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
class JsonCallbackAdapterFactory {

  private final ObjectMapper mapper;
  private final ObjectProvider<JsonCallbackRouteRegistry> routeRegistry;

  JsonCallbackAdapterFactory(ObjectMapper mapper,
                             ObjectProvider<JsonCallbackRouteRegistry> routeRegistry) {
    this.mapper = mapper;
    this.routeRegistry = routeRegistry;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  AboutToSubmit aboutToSubmit(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> {
      Object response = invoke(callbackUrl, eventId, details, detailsBefore);
      JsonNode node = mapper.valueToTree(response == null ? Map.of() : response);
      Object data = node.has("data")
          ? mapper.convertValue(node.get("data"), details.getData().getClass())
          : details.getData();
      return AboutToStartOrSubmitResponse.builder()
          .data(data)
          .errors(listNode(node, "errors"))
          .warnings(listNode(node, "warnings"))
          .state(state(node, details))
          .securityClassification(textNode(node, "security_classification"))
          .build();
    };
  }

  @SuppressWarnings("rawtypes")
  Submitted submitted(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> {
      Object response = invoke(callbackUrl, eventId, details, detailsBefore);
      if (response == null) {
        return SubmittedCallbackResponse.builder().build();
      }
      return mapper.convertValue(response, SubmittedCallbackResponse.class);
    };
  }

  private Object invoke(String callbackUrl,
                        String eventId,
                        CaseDetails<?, ?> details,
                        CaseDetails<?, ?> detailsBefore) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("case_details", toCcdCaseDetails(details));
    payload.put("case_details_before", detailsBefore == null ? null : toCcdCaseDetails(detailsBefore));
    payload.put("event_id", eventId);

    String authorisation = CallbackInvocationContext.authorisation().orElse("");
    return routeRegistry.getObject().invoke(callbackUrl, payload, authorisation);
  }

  private uk.gov.hmcts.reform.ccd.client.model.CaseDetails toCcdCaseDetails(CaseDetails<?, ?> details) {
    return mapper.convertValue(details, uk.gov.hmcts.reform.ccd.client.model.CaseDetails.class);
  }

  @SuppressWarnings("unchecked")
  private <S> S state(JsonNode node, CaseDetails<?, S> details) {
    String state = textNode(node, "state");
    if (state == null) {
      return null;
    }
    S current = details.getState();
    if (current instanceof Enum<?> currentEnum) {
      return (S) Enum.valueOf((Class) currentEnum.getDeclaringClass(), state);
    }
    return (S) state;
  }

  private java.util.List<String> listNode(JsonNode node, String field) {
    if (!node.has(field) || node.get(field).isNull()) {
      return java.util.List.of();
    }
    return mapper.convertValue(
        node.get(field),
        mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)
    );
  }

  private String textNode(JsonNode node, String field) {
    return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
  }
}
