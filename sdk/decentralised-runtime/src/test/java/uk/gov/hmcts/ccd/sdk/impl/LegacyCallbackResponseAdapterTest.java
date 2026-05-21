package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyCallbackResponseAdapterTest {

  private final LegacyCallbackResponseAdapter adapter = new LegacyCallbackResponseAdapter(new ObjectMapper());

  @Test
  void adaptsAboutToSubmitResponseStructurally() {
    var response = adapter.aboutToSubmit(new JsonAboutToSubmitResponse(
        Map.of("note", "updated"),
        "Submitted",
        "PUBLIC",
        List.of("error"),
        List.of("warning")
    ));

    assertEquals("updated", response.data().get("note").asText());
    assertEquals("Submitted", response.state());
    assertEquals(SecurityClassification.PUBLIC, response.securityClassification());
    assertEquals(List.of("error"), response.errors());
    assertEquals(List.of("warning"), response.warnings());
  }

  @Test
  void adaptsSubmittedResponseEntityStructurally() {
    var response = adapter.submitted(ResponseEntity.ok(new JsonSubmittedResponse("header", "body")));

    assertEquals("header", response.getConfirmationHeader());
    assertEquals("body", response.getConfirmationBody());
  }

  private record JsonAboutToSubmitResponse(
      Map<String, Object> data,
      String state,
      String securityClassification,
      List<String> errors,
      List<String> warnings
  ) {
  }

  private record JsonSubmittedResponse(
      @JsonProperty("confirmation_header") String confirmationHeader,
      @JsonProperty("confirmation_body") String confirmationBody
  ) {
  }
}
