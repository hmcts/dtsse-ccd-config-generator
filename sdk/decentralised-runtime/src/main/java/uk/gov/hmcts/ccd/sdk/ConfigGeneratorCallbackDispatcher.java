package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RequiredArgsConstructor
@Configuration
class ConfigGeneratorCallbackDispatcher {

  private final CcdCallbackExecutor executor;
  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;

  @SneakyThrows
  public SubmitDispatchOutcome prepareSubmit(DecentralisedCaseEvent event,
                                             MultiValueMap<String, String> urlParams) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    DecentralisedSubmitEventResponse response = new DecentralisedSubmitEventResponse();
    AfterSubmitCallbackResponse afterSubmit = null;

    if (eventConfig.getSubmitHandler() != null) {
      var config = registry.getRequired(caseType);

      Object domainCaseData = mapper.convertValue(
          event.getCaseDetails().getData(),
          config.getCaseClass()
      );
      long caseRef = event.getCaseDetails().getReference();

      SubmitResponse submitResponse = eventConfig.getSubmitHandler()
          .submit(new uk.gov.hmcts.ccd.sdk.api.EventPayload(caseRef, domainCaseData, urlParams));

      Map<String, JsonNode> normalisedData = domainCaseData == null
          ? Map.of()
          : mapper.convertValue(domainCaseData, new TypeReference<Map<String, JsonNode>>() {});
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
    } else if (eventConfig.getAboutToSubmitCallback() != null) {
      CallbackRequest request = buildCallbackRequest(event);
      AboutToStartOrSubmitResponse callbackResponse = executor.aboutToSubmit(request);

      Map<String, JsonNode> normalisedData = callbackResponse.getData() == null
          ? Map.of()
          : mapper.convertValue(callbackResponse.getData(),
              new TypeReference<Map<String, JsonNode>>() {}
          );
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

    boolean hasSubmittedCallback = eventConfig.getSubmittedCallback() != null;
    return new SubmitDispatchOutcome(response, Optional.ofNullable(afterSubmit), hasSubmittedCallback);
  }

  @SneakyThrows
  public Optional<SubmittedCallbackResponse> runSubmittedCallback(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (eventConfig.getSubmittedCallback() == null) {
      return Optional.empty();
    }

    CallbackRequest request = buildCallbackRequest(event);
    SubmittedCallbackResponse submitted = invokeSubmitted(caseType, eventId, eventConfig, request);
    return Optional.ofNullable(submitted);
  }

  public Optional<String> nameForState(String caseType, String stateId) {
    return registry.labelForState(caseType, stateId);
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

  private SubmittedCallbackResponse invokeSubmitted(String caseType,
                                                     String eventId,
                                                     Event<?, ?, ?> eventConfig,
                                                     CallbackRequest request) {
    var retriesConfig = eventConfig.getRetries().get(Webhook.Submitted);
    int retries = retriesConfig == null || retriesConfig.isEmpty() ? 1 : 3;
    for (int attempt = 0; attempt < retries; attempt++) {
      try {
        var submitted = executor.submitted(request);
        log.debug("Submitted callback returned header={} body={}",
            submitted != null ? submitted.getConfirmationHeader() : null,
            submitted != null ? submitted.getConfirmationBody() : null);
        return submitted;
      } catch (Exception ex) {
        log.warn("Unsuccessful submitted callback for caseType={} eventId={}", caseType, eventId, ex);
      }
    }
    return SubmittedCallbackResponse.builder().build();
  }

  public record SubmitDispatchOutcome(DecentralisedSubmitEventResponse response,
                                      Optional<AfterSubmitCallbackResponse> afterSubmitResponse,
                                      boolean hasSubmittedCallback) {}

  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return executor.aboutToSubmit(request);
  }
}
