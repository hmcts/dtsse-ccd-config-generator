package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonDefinitionSubmissionHandler implements CaseSubmissionHandler {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final CallbackDispatchService callbackDispatchService;
  private final ObjectMapper mapper;

  @Override
  public CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event) {
    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var submitResponse = prepareLegacySubmit(event);

    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

    var errors = submitResponse.getErrors();
    var warnings = submitResponse.getWarnings();
    JsonNode dataSnapshot = mapper.valueToTree(event.getCaseDetails().getData());
    var state = Optional.ofNullable(event.getCaseDetails().getState());
    var securityClassification = Optional.ofNullable(event.getCaseDetails().getSecurityClassification())
        .map(SecurityClassification::name)
        .map(Classification::valueOf);

    return new CaseSubmissionHandlerResult(
        Optional.ofNullable(dataSnapshot),
        state,
        securityClassification,
        () -> {
          var builder = SubmitResponse.builder()
              .errors(errors)
              .warnings(warnings);

          var submittedResponse = runSubmittedCallback(event).orElse(null);

          if (submittedResponse != null) {
            builder.confirmationHeader(submittedResponse.getConfirmationHeader());
            builder.confirmationBody(submittedResponse.getConfirmationBody());
          }
          securityClassification.ifPresent(builder::caseSecurityClassification);
          return builder.build();
        });
  }

  private DecentralisedSubmitEventResponse prepareLegacySubmit(DecentralisedCaseEvent event) {
    var request = buildCallbackRequest(event);
    var response = new DecentralisedSubmitEventResponse();

    var callbackResponse = callbackDispatchService.dispatchToHandlersAboutToSubmit(request);

    if (callbackResponse != null) {
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

        response.setErrors(callbackResponse.getErrors());
        response.setWarnings(callbackResponse.getWarnings());
      }
    }

    return response;
  }

  private Optional<SubmittedCallbackResponse> runSubmittedCallback(DecentralisedCaseEvent event) {

    CallbackRequest request = buildCallbackRequest(event);
    var submitted = callbackDispatchService.dispatchToHandlersSubmitted(request);
    return Optional.ofNullable(submitted);
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
}
