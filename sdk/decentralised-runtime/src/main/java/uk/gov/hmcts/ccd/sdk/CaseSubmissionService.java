package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
  private final CaseDataRepository caseDataRepository;
  private final CaseViewLoader caseViewLoader;

  public DecentralisedSubmitEventResponse submit(DecentralisedCaseEvent event,
                                                 String authorisation,
                                                 UUID idempotencyKey) {
    var eventConfig = resolvedConfigRegistry.getRequiredEvent(
        event.getEventDetails().getCaseType(), event.getEventDetails().getEventId());
    var user = idam.retrieveUser(authorisation);
    var handler = eventConfig.getSubmitHandler() != null ? submitHandler : legacyHandler;

    try {
      // The result of the transaction can be either an idempotency hit or a new submission.
      TransactionResult transactionResult = transactionTemplate.execute(status ->
          executeSubmissionInTransaction(event, user, handler, idempotencyKey)
      );

      return transactionResult.existingEventId()
          .map(eventId -> replayIdempotentRequest(event.getCaseDetails().getReference(), eventId))
          .orElseGet(() -> buildSuccessResponse(transactionResult.submissionOutcome().orElseThrow()));

    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return response;
    }
  }

  /**
   * Encapsulates all database operations that must run within a single transaction.
   */
  private TransactionResult executeSubmissionInTransaction(DecentralisedCaseEvent event,
                                                           IdamService.User user,
                                                           CaseSubmissionHandler handler,
                                                           UUID idempotencyKey) {
    // Idempotency Check inside the transaction to ensure atomicity
    Optional<Long> existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
        idempotencyKey, event.getCaseDetails().getReference()
    );

    if (existingEventId.isPresent()) {
      return new TransactionResult(existingEventId, Optional.empty());
    }

    // Delegate to the specific handler to apply the change
    var handlerResult = handler.apply(event);
    applyHandlerChanges(event, handlerResult);

    // Bookkeeping: update case_data metadata and optionally the legacy json blob
    upsertCase(event, handlerResult.dataUpdate());
    DecentralisedCaseDetails savedCaseDetails = caseViewLoader.load(event.getCaseDetails().getReference());
    caseEventHistoryService.saveAuditRecord(event, user, savedCaseDetails.getCaseDetails(), idempotencyKey);

    var outcome = new SubmissionOutcome(savedCaseDetails, handlerResult.responseSupplier());
    return new TransactionResult(Optional.empty(), Optional.of(outcome));
  }

  /**
   * Builds the final HTTP response DTO from a successful transaction outcome.
   */
  private DecentralisedSubmitEventResponse buildSuccessResponse(SubmissionOutcome outcome) {
    DecentralisedSubmitEventResponse response = new DecentralisedSubmitEventResponse();
    SubmitResponse<?> handlerResponse = outcome.responseSupplier().get();

    response.setCaseDetails(outcome.savedCaseDetails());
    response.setErrors(handlerResponse.getErrors());
    response.setWarnings(handlerResponse.getWarnings());

    AfterSubmitCallbackResponse afterSubmit = new AfterSubmitCallbackResponse();
    afterSubmit.setConfirmationHeader(handlerResponse.getConfirmationHeader());
    afterSubmit.setConfirmationBody(handlerResponse.getConfirmationBody());
    ResponseEntity<AfterSubmitCallbackResponse> entity = ResponseEntity.ok(afterSubmit);
    response.getCaseDetails().getCaseDetails().setAfterSubmitCallbackResponseEntity(entity);

    return response;
  }

  /**
   * Handles replaying a previous event in case of an idempotency hit.
   */
  private DecentralisedSubmitEventResponse replayIdempotentRequest(long caseReference, long eventId) {
    var details = caseDataRepository.caseDetailsAtEvent(caseReference, eventId);
    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return response;
  }

  private void applyHandlerChanges(DecentralisedCaseEvent event,
                                   CaseSubmissionHandler.CaseSubmissionHandlerResult handlerResult) {
    handlerResult.state().ifPresent(event.getCaseDetails()::setState);
    handlerResult.securityClassification()
        .map(classification -> SecurityClassification.valueOf(classification.name()))
        .ifPresent(event.getCaseDetails()::setSecurityClassification);
  }

  private void upsertCase(DecentralisedCaseEvent event, Optional<JsonNode> dataUpdate) {
    try {
      caseDataRepository.upsertCase(event, dataUpdate);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently", e);
    }
  }


  private record SubmissionOutcome(
      DecentralisedCaseDetails savedCaseDetails,
      Supplier<SubmitResponse<?>> responseSupplier
  ) {}

  /**
   * A wrapper that represents the two possible outcomes of the transactional block:
   * either an idempotency hit (existingEventId is present) or a new submission (submissionOutcome is present).
   */
  private record TransactionResult(
      Optional<Long> existingEventId,
      Optional<SubmissionOutcome> submissionOutcome
  ) {}
}
