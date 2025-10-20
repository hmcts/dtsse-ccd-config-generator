package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

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
    AtomicReference<DecentralisedCaseDetails> r = new AtomicReference<>();

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

        // Let the handler apply the change
        var handlerResult = handler.apply(event);
        // If the handler wishes to override state or security classification, apply those changes
        var requestedState = handlerResult.state();
        requestedState.ifPresent(event.getCaseDetails()::setState);
        handlerResult.securityClassification()
            .map(SecurityClassification::valueOf)
            .ifPresent(event.getCaseDetails()::setSecurityClassification);

        // Persist changes, including legacy blob changes where used
        upsertCase(event, handlerResult.dataUpdate());

        r.set(blobRepository.getCase(event.getCaseDetails().getReference()));
        var currentView = r.get().getCaseDetails();
        caseEventHistoryService.saveAuditRecord(event, user, currentView, idempotencyUuid);

        return new SubmissionTransactionResult(existingEventId, handlerResult.responseSupplier());
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

    var res = new DecentralisedSubmitEventResponse();
    SubmitResponse<?> txResponse = txResult.responseSupplier().get();
    if (txResponse == null) {
      txResponse = SubmitResponse.builder().build();
    }
    res.setCaseDetails(r.get());
    res.setErrors(txResponse.getErrors());
    res.setWarnings(txResponse.getWarnings());
    var afterSubmitResponse = new AfterSubmitCallbackResponse();
    afterSubmitResponse.setConfirmationHeader(txResponse.getConfirmationHeader());
    afterSubmitResponse.setConfirmationBody(txResponse.getConfirmationBody());
    var responseEntity = ResponseEntity.ok(afterSubmitResponse);
    res.getCaseDetails().getCaseDetails().setAfterSubmitCallbackResponseEntity(responseEntity);

    return res;
  }

  private DecentralisedSubmitEventResponse replayProcessed(DecentralisedCaseEvent event,
                                                           long eventId) {
    var details = blobRepository.caseDetailsAtEvent(event.getCaseDetails().getReference(), eventId)
        .orElseGet(() -> blobRepository.getCase(event.getCaseDetails().getReference()));
    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return response;
  }

  private void upsertCase(DecentralisedCaseEvent event, Optional<JsonNode> dataUpdate) {
    try {
      blobRepository.upsertCase(event, dataUpdate);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently", e);
    }
  }

  private record SubmissionTransactionResult(java.util.Optional<Long> existingEventId,
                                             java.util.function.Supplier<SubmitResponse<?>> responseSupplier) {}
}
