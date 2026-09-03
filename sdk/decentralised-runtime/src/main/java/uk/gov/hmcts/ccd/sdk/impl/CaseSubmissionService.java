package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

@Service
@RequiredArgsConstructor
public class CaseSubmissionService {

  private final ResolvedConfigRegistry resolvedConfigRegistry;
  private final DecentralisedSubmissionHandler submitHandler;
  private final LegacyCallbackSubmissionHandler legacyHandler;
  private final IdamService idam;
  private final CaseEventTransactionCoordinator transactionCoordinator;
  private final CaseDataRepository caseDataRepository;

  public DecentralisedSubmitEventResponse submit(DecentralisedCaseEvent event,
                                                 String authorisation,
                                                 UUID idempotencyKey) {
    var eventConfig = getEventConfig(event);
    var user = idam.retrieveUser(authorisation);
    var handler = eventConfig.getSubmitHandler() != null ? submitHandler : legacyHandler;

    try {
      var transactionResult =
          transactionCoordinator.execute(
              event.getCaseDetails().getReference(),
              idempotencyKey,
              () -> prepareSubmission(event, user, handler)
          );

      return transactionResult.existingEventId()
          .map(eventId -> replayIdempotentRequest(event.getCaseDetails().getReference(), eventId))
          .orElseGet(() -> {
            var created = transactionResult.createdEvent().orElseThrow();
            return buildSuccessResponse(new SubmissionOutcome(created.savedCase(), created.result()));
          });

    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return response;
    }
  }

  private CaseEventTransactionCoordinator.CaseEventWrite<Supplier<SubmitResponse<?>>> prepareSubmission(
      DecentralisedCaseEvent event,
      IdamService.User user,
      CaseSubmissionHandler handler
  ) {
    var handlerResult = handler.apply(event, user.authToken());
    applyHandlerChanges(event, handlerResult);

    return new CaseEventTransactionCoordinator.CaseEventWrite<>(
        event,
        user,
        handlerResult.dataUpdate(),
        handlerResult.significantItem(),
        handlerResult.responseSupplier()
    );
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
    handlerResult.eventMetadata().ifPresent(metadata -> applyEventMetadata(event, metadata));
  }

  private void applyEventMetadata(DecentralisedCaseEvent event, EventMetadata eventMetadata) {
    var eventDetails = event.getEventDetails();
    eventDetails.setSummary(Optional.ofNullable(eventMetadata.getSummary()).orElse(eventDetails.getSummary()));
    eventDetails.setDescription(
        Optional.ofNullable(eventMetadata.getDescription()).orElse(eventDetails.getDescription())
    );
  }

  private uk.gov.hmcts.ccd.sdk.api.Event<?, ?, ?> getEventConfig(DecentralisedCaseEvent event) {
    try {
      return resolvedConfigRegistry.getRequiredEvent(
          event.getEventDetails().getCaseType(), event.getEventDetails().getEventId());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private record SubmissionOutcome(
      DecentralisedCaseDetails savedCaseDetails,
      Supplier<SubmitResponse<?>> responseSupplier
  ) {}

}
