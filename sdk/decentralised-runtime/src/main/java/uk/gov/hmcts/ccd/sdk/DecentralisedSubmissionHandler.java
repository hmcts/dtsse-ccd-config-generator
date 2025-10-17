package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

/**
 * Submission flow that relies on the decentralised submit handler instead of the
 * legacy about-to-submit callback sequence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DecentralisedSubmissionHandler implements CaseSubmissionHandler {

  private final ConfigGeneratorCallbackDispatcher dispatcher;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final CaseEventHistoryService caseEventHistoryService;
  private final BlobRepository blobRepository;

  @Override
  @SneakyThrows
  @Transactional
  public CaseSubmissionResponse handle(DecentralisedCaseEvent event,
                                       IdamService.User user,
                                       String idempotencyKey) {

    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    UUID idempotencyUuid = UUID.fromString(idempotencyKey);
    boolean alreadyProcessed = idempotencyEnforcer.lockCaseAndCheckProcessed(
        idempotencyUuid,
        event.getCaseDetails().getReference()
    );
    if (alreadyProcessed) {
      return new CaseSubmissionResponse(buildResponse(event, null));
    }

    ConfigGeneratorCallbackDispatcher.SubmitDispatchOutcome outcome;
    try {
      outcome = dispatcher.prepareSubmit(event);

      var submitResponse = outcome.response();
      if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
        throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
      }

      outcome.afterSubmitResponse().ifPresent(afterSubmit ->
          event.getCaseDetails().setAfterSubmitCallbackResponseEntity(ResponseEntity.ok(afterSubmit))
      );

      saveCaseReturningAuditId(event, user, idempotencyUuid);
    } catch (CallbackValidationException e) {
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return new CaseSubmissionResponse(response);
    }

    var finalResponse = buildResponse(event, event.getCaseDetails().getAfterSubmitCallbackResponse());
    return new CaseSubmissionResponse(finalResponse);
  }

  private DecentralisedSubmitEventResponse buildResponse(DecentralisedCaseEvent event,
                                                         AfterSubmitCallbackResponse afterSubmit) {
    SubmittedCallbackResponse submittedResponse = toSubmittedCallbackResponse(afterSubmit);

    var details = blobRepository.getCase(event.getCaseDetails().getReference());
    if (submittedResponse != null) {
      var afterSubmitResponse = new AfterSubmitCallbackResponse();
      afterSubmitResponse.setConfirmationHeader(submittedResponse.getConfirmationHeader());
      afterSubmitResponse.setConfirmationBody(submittedResponse.getConfirmationBody());
      var responseEntity = ResponseEntity.ok(afterSubmitResponse);
      details.getCaseDetails().setAfterSubmitCallbackResponseEntity(responseEntity);
    }

    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return response;
  }

  @SneakyThrows
  private long saveCaseReturningAuditId(DecentralisedCaseEvent event,
                                        IdamService.User user,
                                        UUID idempotencyKey) {
    try {
      long caseDataId = blobRepository.upsertCase(event);
      var currentView = blobRepository.getCase(event.getCaseDetails().getReference()).getCaseDetails();
      return caseEventHistoryService.saveAuditRecord(event, user, currentView, caseDataId, idempotencyKey);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
    }
  }

  private SubmittedCallbackResponse toSubmittedCallbackResponse(AfterSubmitCallbackResponse response) {
    if (response == null) {
      return null;
    }
    if (response.getConfirmationHeader() == null && response.getConfirmationBody() == null) {
      return null;
    }
    return SubmittedCallbackResponse.builder()
        .confirmationHeader(response.getConfirmationHeader())
        .confirmationBody(response.getConfirmationBody())
        .build();
  }
}
