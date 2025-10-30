package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

import java.util.UUID;

public class ServicePersistenceControllerTest {

  private final CaseSubmissionService submissionService = mock(CaseSubmissionService.class);
  private final CaseEventHistoryService caseEventHistoryService = mock(CaseEventHistoryService.class);
  private final SupplementaryDataService supplementaryDataService = mock(SupplementaryDataService.class);
  private final CaseViewLoader caseViewLoader = mock(CaseViewLoader.class);

  private final ServicePersistenceController controller = new ServicePersistenceController(
      submissionService,
      caseEventHistoryService,
      supplementaryDataService,
      caseViewLoader
  );

  @Test
  void createEventWithoutAuthorizationReturnsUnauthorized() {
    DecentralisedCaseEvent event = mock(DecentralisedCaseEvent.class);

    ResponseEntity<DecentralisedSubmitEventResponse> response = controller.createEvent(
        event,
        null,
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
