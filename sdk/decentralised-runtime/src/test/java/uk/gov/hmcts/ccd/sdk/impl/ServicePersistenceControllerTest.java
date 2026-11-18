package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;

public class ServicePersistenceControllerTest {

  private final CaseSubmissionService submissionService = mock(CaseSubmissionService.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final SupplementaryDataService supplementaryDataService = mock(SupplementaryDataService.class);
  private final CaseProjectionService caseProjectionService = mock(CaseProjectionService.class);

  private final ServicePersistenceController controller = new ServicePersistenceController(
      submissionService,
      auditEventService,
      supplementaryDataService,
      caseProjectionService
  );

  @Test
  void createEventWithEmptyAuthorizationReturnsUnauthorized() {
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        "",
        UUID.randomUUID()
    );

    assertThat(response.getStatusCodeValue()).isEqualTo(401);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("Authorization header is required");

    verifyNoInteractions(submissionService);
  }

  @Test
  void createEventWithBlankAuthorizationReturnsUnauthorized() {
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        " ",
        UUID.randomUUID()
    );

    assertThat(response.getStatusCodeValue()).isEqualTo(401);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("Authorization header is required");

    verifyNoInteractions(submissionService);
  }

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void createEventReturnsTheClientContextACallbackReturned() {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);
    UUID idempotencyKey = UUID.randomUUID();
    when(submissionService.submit(event, "Bearer token", idempotencyKey)).thenAnswer(invocation -> {
      ClientContextPassthrough.returned("eyJ1c2VyX3Rhc2siOnt9fQ==");
      return new DecentralisedSubmitEventResponse();
    });

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        "Bearer token",
        idempotencyKey
    );

    assertThat(response.getHeaders().getFirst("Client-Context")).isEqualTo("eyJ1c2VyX3Rhc2siOnt9fQ==");
  }

  @Test
  void createEventReturnsNoClientContextWhenNoCallbackReturnedOne() {
    MockHttpServletRequest submission = new MockHttpServletRequest();
    submission.addHeader("Client-Context", "eyJ1c2VyX3Rhc2siOnt9fQ==");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(submission));
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);
    UUID idempotencyKey = UUID.randomUUID();
    when(submissionService.submit(event, "Bearer token", idempotencyKey))
        .thenReturn(new DecentralisedSubmitEventResponse());

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        "Bearer token",
        idempotencyKey
    );

    assertThat(response.getHeaders().containsKey("Client-Context")).isFalse();
  }

  @Test
  void createEventWithValidHeadersCallsSubmissionService() {
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);
    UUID idempotencyKey = UUID.randomUUID();
    var expectedResponse = new DecentralisedSubmitEventResponse();
    when(submissionService.submit(event, "Bearer token", idempotencyKey)).thenReturn(expectedResponse);

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        "Bearer token",
        idempotencyKey
    );

    assertThat(response.getStatusCodeValue()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(expectedResponse);

    verify(submissionService).submit(event, "Bearer token", idempotencyKey);
    verifyNoMoreInteractions(submissionService);
  }
}
