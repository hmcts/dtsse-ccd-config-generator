package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

public class CaseControllerTest {

  private final CaseSubmissionService submissionService = mock(CaseSubmissionService.class);
  private final CaseEventHistoryService caseEventHistoryService = mock(CaseEventHistoryService.class);
  private final SupplementaryDataService supplementaryDataService = mock(SupplementaryDataService.class);
  private final CaseViewLoader caseViewLoader = mock(CaseViewLoader.class);

  private final CaseController controller = new CaseController(
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
        "d65f3f1d-6b44-4fd8-a6ec-e4a7a7d5fd1e"
    );

    assertThat(response.getStatusCodeValue()).isEqualTo(401);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("Authorization header is required");

    verifyNoInteractions(submissionService);
  }
}
