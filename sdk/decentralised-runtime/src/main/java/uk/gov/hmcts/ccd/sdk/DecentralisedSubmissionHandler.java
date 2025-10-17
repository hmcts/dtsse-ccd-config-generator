package uk.gov.hmcts.ccd.sdk;

import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
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

  private final TransactionTemplate transactionTemplate;
  private final ConfigGeneratorCallbackDispatcher dispatcher;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final IdamService idam;
  private final CaseEventHistoryService caseEventHistoryService;
  private final BlobRepository blobRepository;

  @Override
  @SneakyThrows
  public DecentralisedSubmitEventResponse handle(DecentralisedCaseEvent event,
                                                 String authorisation,
                                                 String idempotencyKey) {

    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var user = idam.retrieveUser(authorisation);
    AtomicReference<ConfigGeneratorCallbackDispatcher.SubmitDispatchOutcome> dispatchOutcome =
        new AtomicReference<>();
    boolean processed;

    try {
      processed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
        if (idempotencyEnforcer.markProcessedReturningIsAlreadyProcessed(idempotencyKey)) {
          return false;
        }

        var outcome = dispatcher.prepareSubmit(event);
        dispatchOutcome.set(outcome);

        var submitResponse = outcome.response();
        if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
          throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
        }

        outcome.afterSubmitResponse().ifPresent(afterSubmit ->
            event.getCaseDetails().setAfterSubmitCallbackResponseEntity(ResponseEntity.ok(afterSubmit))
        );

        saveCaseReturningAuditId(event, user);
        return true;
      }));
    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return response;
    }

    var outcome = dispatchOutcome.get();
    boolean runSubmitted = processed && outcome != null && outcome.hasSubmittedCallback();

    SubmittedCallbackResponse submittedResponse = null;
    if (runSubmitted) {
      submittedResponse = dispatcher.runSubmittedCallback(event).orElse(null);
    }

    var details = blobRepository.getCase(event.getCaseDetails().getReference());
    if (submittedResponse == null) {
      submittedResponse = toSubmittedCallbackResponse(
          event.getCaseDetails().getAfterSubmitCallbackResponse());
    }
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
  private long saveCaseReturningAuditId(DecentralisedCaseEvent event, IdamService.User user) {
    try {
      long caseDataId = blobRepository.upsertCase(event);
      var currentView = blobRepository.getCase(event.getCaseDetails().getReference()).getCaseDetails();
      return caseEventHistoryService.saveAuditRecord(event, user, currentView, caseDataId);
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
