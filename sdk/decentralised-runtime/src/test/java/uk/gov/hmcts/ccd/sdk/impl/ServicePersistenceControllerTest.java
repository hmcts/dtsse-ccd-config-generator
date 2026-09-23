package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;

public class ServicePersistenceControllerTest {

  // A request without a usable Authorization header must be rejected before any case data is
  // touched, so the controller has no services to reach.
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new ServicePersistenceController(null, null, null, null))
      .build();

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void createEventWithoutAuthorizationIsUnauthorized(String authorization) throws Exception {
    mockMvc.perform(post("/ccd-persistence/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .header("Authorization", authorization)
            .header(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0]").value("Authorization header is required"));
  }

  @Test
  void createEventWithValidHeadersCallsSubmissionService() {
    CaseSubmissionService submissionService = mock(CaseSubmissionService.class);
    ServicePersistenceController controller = new ServicePersistenceController(
        submissionService, null, null, null);
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);
    UUID idempotencyKey = UUID.randomUUID();
    var expectedResponse = new DecentralisedSubmitEventResponse();
    when(submissionService.submit(event, "Bearer token", idempotencyKey)).thenReturn(expectedResponse);

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event, "Bearer token", idempotencyKey);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(expectedResponse);
    verify(submissionService).submit(event, "Bearer token", idempotencyKey);
    verifyNoMoreInteractions(submissionService);
  }
}
