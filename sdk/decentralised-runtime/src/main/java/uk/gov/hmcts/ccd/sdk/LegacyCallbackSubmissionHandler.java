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
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
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

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final ResolvedConfigRegistry registry;
  private final CcdCallbackExecutor executor;
  private final ObjectMapper mapper;
  private final BlobRepository blobRepository;

  @Override
  public java.util.function.Supplier<DecentralisedSubmitEventResponse> apply(DecentralisedCaseEvent event) {
    log.info("[legacy] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = prepareLegacySubmit(event);

    var submitResponse = outcome.response();
    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

    boolean runSubmitted = outcome.runSubmittedCallback();

    return () -> {
      SubmittedCallbackResponse submittedResponse = null;
      if (runSubmitted) {
        submittedResponse = runSubmittedCallback(event).orElse(null);
      }

      if (submittedResponse == null) {
        submittedResponse = toSubmittedCallbackResponse(
            event.getCaseDetails().getAfterSubmitCallbackResponse());
      }

      return buildResponse(event, submittedResponse);
    };
  }

  private LegacySubmitOutcome prepareLegacySubmit(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    var response = new DecentralisedSubmitEventResponse();

    if (eventConfig.getAboutToSubmitCallback() != null) {
      CallbackRequest request = buildCallbackRequest(event);
      AboutToStartOrSubmitResponse callbackResponse = executor.aboutToSubmit(request);

      Map<String, JsonNode> normalisedData = callbackResponse.getData() == null
          ? Map.of()
          : mapper.convertValue(callbackResponse.getData(), JSON_NODE_MAP);
      event.getCaseDetails().setData(normalisedData);

      if (callbackResponse.getState() != null) {
        event.getCaseDetails().setState(callbackResponse.getState().toString());
      }
      if (callbackResponse.getSecurityClassification() != null) {
        event.getCaseDetails().setSecurityClassification(
            SecurityClassification.valueOf(callbackResponse.getSecurityClassification())
        );
      }

      response.setErrors(callbackResponse.getErrors());
      response.setWarnings(callbackResponse.getWarnings());
    }

    boolean hasSubmitted = eventConfig.getSubmittedCallback() != null;
    return new LegacySubmitOutcome(response, hasSubmitted);
  }

  private Optional<SubmittedCallbackResponse> runSubmittedCallback(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (eventConfig.getSubmittedCallback() == null) {
      return Optional.empty();
    }

    CallbackRequest request = buildCallbackRequest(event);
    var retriesConfig = eventConfig.getRetries().get(Webhook.Submitted);
    int retries = retriesConfig == null || retriesConfig.isEmpty() ? 1 : 3;

    for (int attempt = 0; attempt < retries; attempt++) {
      try {
        SubmittedCallbackResponse submitted = executor.submitted(request);
        log.debug("Submitted callback returned header={} body={}",
            submitted != null ? submitted.getConfirmationHeader() : null,
            submitted != null ? submitted.getConfirmationBody() : null);
        return Optional.ofNullable(submitted);
      } catch (Exception ex) {
        log.warn("Unsuccessful submitted callback for caseType={} eventId={}", caseType, eventId, ex);
      }
    }

    return Optional.of(SubmittedCallbackResponse.builder().build());
  }

  private CallbackRequest buildCallbackRequest(DecentralisedCaseEvent event) {
    CaseDetails caseDetails = mapper.convertValue(event.getCaseDetails(), CaseDetails.class);
    CaseDetails caseDetailsBefore = event.getCaseDetailsBefore() == null
        ? null
        : mapper.convertValue(event.getCaseDetailsBefore(), CaseDetails.class);

    return CallbackRequest.builder()
        .caseDetails(caseDetails)
        .caseDetailsBefore(caseDetailsBefore)
        .eventId(event.getEventDetails().getEventId())
        .build();
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

  private record LegacySubmitOutcome(DecentralisedSubmitEventResponse response,
                                     boolean runSubmittedCallback) {}
}
