package uk.gov.hmcts.ccd.sdk;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Legacy submission flow that still relies on the "about to submit" and
 * "submitted" callbacks. Extracted so we can contain legacy-specific behaviour
 * outside the controller and share the top-level orchestration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LegacyCallbackSubmissionHandler implements CaseSubmissionHandler {

  private final TransactionTemplate transactionTemplate;
  private final ConfigGeneratorCallbackDispatcher dispatcher;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final CaseEventHistoryService caseEventHistoryService;
  private final BlobRepository blobRepository;

  @Override
  @SneakyThrows
  public CaseSubmissionResponse handle(DecentralisedCaseEvent event,
                                       IdamService.User user,
                                       String idempotencyKey) {

    log.info("[legacy] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    UUID idempotencyUuid = UUID.fromString(idempotencyKey);
    AtomicReference<ConfigGeneratorCallbackDispatcher.SubmitDispatchOutcome> dispatchOutcome =
        new AtomicReference<>();
    AtomicBoolean alreadyProcessed = new AtomicBoolean(false);
    boolean processed;

    try {
      processed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
        if (idempotencyEnforcer.lockCaseAndCheckProcessed(
            idempotencyUuid,
            event.getCaseDetails().getReference()
        )) {
          alreadyProcessed.set(true);
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

        saveCaseReturningAuditId(event, user, idempotencyUuid);
        return true;
      }));
    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return new CaseSubmissionResponse(response);
    }

    if (alreadyProcessed.get()) {
      return new CaseSubmissionResponse(buildResponse(event, (SubmittedCallbackResponse) null));
    }

    var outcome = dispatchOutcome.get();
    boolean runSubmitted = processed && outcome != null && outcome.hasSubmittedCallback();
    var response = new DecentralisedSubmitEventResponse();
    Runnable postCommit = () -> {
      SubmittedCallbackResponse submittedResponse = null;
      if (runSubmitted) {
        submittedResponse = dispatcher.runSubmittedCallback(event).orElse(null);
      }

      if (submittedResponse == null) {
        submittedResponse = toSubmittedCallbackResponse(
            event.getCaseDetails().getAfterSubmitCallbackResponse());
      }

      populateResponse(event, response, submittedResponse);
    };

    return new CaseSubmissionResponse(response, Optional.of(postCommit));
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

  private DecentralisedSubmitEventResponse buildResponse(DecentralisedCaseEvent event,
                                                         SubmittedCallbackResponse submittedResponse) {
    var response = new DecentralisedSubmitEventResponse();
    populateResponse(event, response, submittedResponse);
    return response;
  }

  private void populateResponse(DecentralisedCaseEvent event,
                                DecentralisedSubmitEventResponse response,
                                SubmittedCallbackResponse submittedResponse) {
    var details = blobRepository.getCase(event.getCaseDetails().getReference());
    if (submittedResponse != null) {
      var afterSubmitResponse = new AfterSubmitCallbackResponse();
      afterSubmitResponse.setConfirmationHeader(submittedResponse.getConfirmationHeader());
      afterSubmitResponse.setConfirmationBody(submittedResponse.getConfirmationBody());
      var responseEntity = ResponseEntity.ok(afterSubmitResponse);
      details.getCaseDetails().setAfterSubmitCallbackResponseEntity(responseEntity);
    }
    response.setCaseDetails(details);
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
