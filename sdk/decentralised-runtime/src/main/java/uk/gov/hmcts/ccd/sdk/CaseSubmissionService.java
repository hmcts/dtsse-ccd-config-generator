package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

@Service
@RequiredArgsConstructor
public class CaseSubmissionService {

  private final ResolvedConfigRegistry resolvedConfigRegistry;
  private final DecentralisedSubmissionHandler submitHandler;
  private final LegacyCallbackSubmissionHandler legacyHandler;
  private final IdamService idam;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final TransactionTemplate transactionTemplate;
  private final CaseEventHistoryService caseEventHistoryService;
  private final BlobRepository blobRepository;

  public DecentralisedSubmitEventResponse submit(DecentralisedCaseEvent event,
                                                 String authorisation,
                                                 String idempotencyKey) {

    var eventDetails = event.getEventDetails();
    var eventConfig = resolvedConfigRegistry.getRequiredEvent(
        eventDetails.getCaseType(), eventDetails.getEventId());
    var handler = eventConfig.getSubmitHandler() != null ? submitHandler : legacyHandler;
    var user = idam.retrieveUser(authorisation);

    UUID idempotencyUuid = UUID.fromString(idempotencyKey);

    SubmissionTransactionResult txResult;
    try {
      txResult = transactionTemplate.execute(status -> {
        var existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
            idempotencyUuid,
            event.getCaseDetails().getReference()
        );

        if (existingEventId.isPresent()) {
          return new SubmissionTransactionResult(existingEventId, null);
        }

        CaseSubmissionOutcome outcome = handler.apply(event, user, idempotencyUuid);
        if (outcome.caseDataId() > 0) {
          var currentView = blobRepository.getCase(event.getCaseDetails().getReference()).getCaseDetails();
          caseEventHistoryService.saveAuditRecord(event, user, currentView, outcome.caseDataId(), idempotencyUuid);
        }

        return new SubmissionTransactionResult(existingEventId, outcome);
      });
    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return response;
    }

    if (txResult == null) {
      throw new IllegalStateException("Submission outcome missing after transaction completion.");
    }

    if (txResult.existingEventId().isPresent()) {
      return replayProcessed(event, txResult.existingEventId().get());
    }

    CaseSubmissionOutcome outcome = txResult.outcome();
    if (outcome == null) {
      throw new IllegalStateException("Submission outcome missing for non-processed path.");
    }

    return outcome.buildResponse();
  }

  private DecentralisedSubmitEventResponse replayProcessed(DecentralisedCaseEvent event,
                                                           long eventId) {
    var details = blobRepository.caseDetailsAtEvent(event.getCaseDetails().getReference(), eventId)
        .orElseGet(() -> blobRepository.getCase(event.getCaseDetails().getReference()));
    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return response;
  }

  private record SubmissionTransactionResult(java.util.Optional<Long> existingEventId,
                                             CaseSubmissionOutcome outcome) {}
}
