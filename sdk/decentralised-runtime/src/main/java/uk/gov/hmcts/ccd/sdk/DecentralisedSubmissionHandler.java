package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

/**
 * Submission flow that relies on the decentralised submit handler instead of the
 * legacy about-to-submit callback sequence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DecentralisedSubmissionHandler implements CaseSubmissionHandler {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;
  private final BlobRepository blobRepository;

  @Override
  public java.util.function.Supplier<DecentralisedSubmitEventResponse> apply(DecentralisedCaseEvent event) {
    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = prepareSubmitHandler(event);

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

  private SubmitHandlerOutcome prepareSubmitHandler(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (eventConfig.getSubmitHandler() == null) {
      throw new IllegalStateException("Submit handler not configured for event " + eventId);
    }

    var response = new DecentralisedSubmitEventResponse();
    AfterSubmitCallbackResponse afterSubmit = null;

    var config = registry.getRequired(caseType);

    Object domainCaseData = mapper.convertValue(
        event.getCaseDetails().getData(),
        config.getCaseClass()
    );
    long caseRef = event.getCaseDetails().getReference();

    // TODO: revisit when CCD resumes sending query params; referer header is absent at the moment.
    var urlParams = new LinkedMultiValueMap<String, String>();
    SubmitResponse submitResponse = eventConfig.getSubmitHandler()
        .submit(new uk.gov.hmcts.ccd.sdk.api.EventPayload(caseRef, domainCaseData, urlParams));

    Map<String, JsonNode> normalisedData = domainCaseData == null
        ? Map.of()
        : mapper.convertValue(domainCaseData, JSON_NODE_MAP);
    event.getCaseDetails().setData(normalisedData);

    if (submitResponse != null) {
      if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
        response.setErrors(submitResponse.getErrors());
      }

      if (submitResponse.getConfirmationHeader() != null
          || submitResponse.getConfirmationBody() != null) {
        afterSubmit = new AfterSubmitCallbackResponse();
        afterSubmit.setConfirmationHeader(submitResponse.getConfirmationHeader());
        afterSubmit.setConfirmationBody(submitResponse.getConfirmationBody());
      }
    }

    return new SubmitHandlerOutcome(response, Optional.ofNullable(afterSubmit));
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

  private record SubmitHandlerOutcome(DecentralisedSubmitEventResponse response,
                                      Optional<AfterSubmitCallbackResponse> afterSubmitResponse) {}
}
