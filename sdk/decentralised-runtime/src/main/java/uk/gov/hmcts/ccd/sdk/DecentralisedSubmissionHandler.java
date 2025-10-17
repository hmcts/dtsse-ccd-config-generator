package uk.gov.hmcts.ccd.sdk;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
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
  private final BlobRepository blobRepository;

  @Override
  @SneakyThrows
  public java.util.function.Supplier<DecentralisedSubmitEventResponse> apply(DecentralisedCaseEvent event) {
    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = dispatcher.prepareSubmit(event);

    var submitResponse = outcome.response();
    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

    outcome.afterSubmitResponse().ifPresent(afterSubmit ->
        event.getCaseDetails().setAfterSubmitCallbackResponseEntity(ResponseEntity.ok(afterSubmit))
    );

    var finalResponse = buildResponse(event, event.getCaseDetails().getAfterSubmitCallbackResponse());
    return () -> finalResponse;
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
