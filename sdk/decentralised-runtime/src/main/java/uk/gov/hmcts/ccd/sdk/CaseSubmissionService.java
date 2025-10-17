package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
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
    boolean decentralisedFlow = eventConfig.getSubmitHandler() != null;
    var handler = decentralisedFlow ? submitHandler : legacyHandler;
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

        var responseSupplier = handler.apply(event);
        upsertCase(event, !decentralisedFlow);
        r.set(blobRepository.getCase(event.getCaseDetails().getReference()));
        var currentView = r.get().getCaseDetails();
        caseEventHistoryService.saveAuditRecord(event, user, currentView, idempotencyUuid);

        return new SubmissionTransactionResult(existingEventId, responseSupplier);
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
    SubmitResponse txResponse = txResult.responseSupplier().get();
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

  private void upsertCase(DecentralisedCaseEvent event, boolean updateBlob) {
    try {
      blobRepository.upsertCase(event, updateBlob);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently", e);
    }
  }

  private record SubmissionTransactionResult(java.util.Optional<Long> existingEventId,
                                             java.util.function.Supplier<SubmitResponse> responseSupplier) {}
}
