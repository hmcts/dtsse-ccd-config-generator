package uk.gov.hmcts.ccd.sdk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
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

  private final ConfigGeneratorCallbackDispatcher dispatcher;
  private final BlobRepository blobRepository;

  @Override
  public java.util.function.Supplier<DecentralisedSubmitEventResponse> apply(DecentralisedCaseEvent event) {
    log.info("[legacy] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = dispatcher.prepareSubmit(event);

    var submitResponse = outcome.response();
    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

    outcome.afterSubmitResponse().ifPresent(afterSubmit ->
        event.getCaseDetails().setAfterSubmitCallbackResponseEntity(ResponseEntity.ok(afterSubmit))
    );

    upsertCase(event);

    boolean runSubmitted = outcome.hasSubmittedCallback();

    return () -> {
      SubmittedCallbackResponse submittedResponse = null;
      if (runSubmitted) {
        submittedResponse = dispatcher.runSubmittedCallback(event).orElse(null);
      }

      if (submittedResponse == null) {
        submittedResponse = toSubmittedCallbackResponse(
            event.getCaseDetails().getAfterSubmitCallbackResponse());
      }

      return buildResponse(event, submittedResponse);
    };
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

  private long upsertCase(DecentralisedCaseEvent event) {
    try {
      return blobRepository.upsertCase(event);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
    }
  }
}
