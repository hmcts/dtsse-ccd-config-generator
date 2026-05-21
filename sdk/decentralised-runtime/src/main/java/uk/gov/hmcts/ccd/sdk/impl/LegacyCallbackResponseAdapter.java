package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
class LegacyCallbackResponseAdapter {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final ObjectMapper mapper;

  LegacyCallbackResponseAdapter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  LegacyAboutToSubmitCallbackResponse aboutToSubmit(Object response) {
    Object body = unwrapResponseEntity(response);
    if (body == null) {
      return new LegacyAboutToSubmitCallbackResponse(Map.of(), null, null, List.of(), List.of());
    }

    if (body instanceof AboutToStartOrSubmitResponse typed) {
      return new LegacyAboutToSubmitCallbackResponse(
          normaliseData(typed.getData()),
          typed.getState() == null ? null : typed.getState().toString(),
          securityClassification(typed.getSecurityClassification()),
          safeList(typed.getErrors()),
          safeList(typed.getWarnings())
      );
    }

    JsonNode root = mapper.valueToTree(body);
    return new LegacyAboutToSubmitCallbackResponse(
        normaliseData(root.get("data")),
        textValue(root, "state"),
        securityClassification(textValue(root, "securityClassification", "security_classification",
            "caseSecurityClassification", "case_security_classification")),
        stringList(root, "errors"),
        stringList(root, "warnings")
    );
  }

  SubmittedCallbackResponse submitted(Object response) {
    Object body = unwrapResponseEntity(response);
    if (body == null) {
      return SubmittedCallbackResponse.builder().build();
    }

    if (body instanceof SubmittedCallbackResponse typed) {
      return typed;
    }

    JsonNode root = mapper.valueToTree(body);
    return SubmittedCallbackResponse.builder()
        .confirmationHeader(textValue(root, "confirmationHeader", "confirmation_header"))
        .confirmationBody(textValue(root, "confirmationBody", "confirmation_body"))
        .build();
  }

  private Object unwrapResponseEntity(Object response) {
    if (response instanceof ResponseEntity<?> entity) {
      if (!entity.getStatusCode().is2xxSuccessful()) {
        throw new ResponseStatusException(
            entity.getStatusCode(),
            "Legacy callback returned HTTP %s".formatted(entity.getStatusCode().value())
        );
      }
      return entity.getBody();
    }
    return response;
  }

  private Map<String, JsonNode> normaliseData(Object data) {
    if (data == null) {
      return Map.of();
    }
    return mapper.convertValue(data, JSON_NODE_MAP);
  }

  private SecurityClassification securityClassification(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return SecurityClassification.valueOf(value);
  }

  private String textValue(JsonNode root, String... fieldNames) {
    if (root == null || root.isNull()) {
      return null;
    }
    for (String fieldName : fieldNames) {
      JsonNode value = root.get(fieldName);
      if (value != null && !value.isNull()) {
        return value.asText();
      }
    }
    return null;
  }

  private List<String> stringList(JsonNode root, String fieldName) {
    if (root == null || root.isNull()) {
      return List.of();
    }
    JsonNode node = root.get(fieldName);
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      return List.of(node.asText());
    }

    List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.asText()));
    return List.copyOf(values);
  }

  private List<String> safeList(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
