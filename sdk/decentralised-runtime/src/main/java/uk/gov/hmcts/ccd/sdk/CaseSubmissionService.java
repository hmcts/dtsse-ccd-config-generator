package uk.gov.hmcts.ccd.sdk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

@Service
@RequiredArgsConstructor
public class CaseSubmissionService {

  private final ResolvedConfigRegistry resolvedConfigRegistry;
  private final DecentralisedSubmissionHandler submitHandler;
  private final LegacyCallbackSubmissionHandler legacyHandler;
  private final IdamService idam;

  public DecentralisedSubmitEventResponse submit(DecentralisedCaseEvent event,
                                                 String authorisation,
                                                 String idempotencyKey) {

    var eventDetails = event.getEventDetails();
    var eventConfig = resolvedConfigRegistry.getRequiredEvent(
        eventDetails.getCaseType(), eventDetails.getEventId());
    var user = idam.retrieveUser(authorisation);

    CaseSubmissionResponse response;
    if (eventConfig.getSubmitHandler() != null) {
      response = submitHandler.handle(event, user, idempotencyKey);
    } else {
      response = legacyHandler.handle(event, user, idempotencyKey);
    }
    response.postCommit().ifPresent(Runnable::run);
    return response.response();
  }
}
