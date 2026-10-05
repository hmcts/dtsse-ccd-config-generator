package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.cdam.CdamAttachService;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Classification;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

/**
 * Legacy submission flow that still relies on the "about to submit" and
 * "submitted" callbacks. Extracted so we can contain legacy-specific behaviour
 * outside the controller and share the top-level orchestration.
 */
@Slf4j
@Component
class LegacyCallbackSubmissionHandler implements CaseSubmissionHandler {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  private final ResolvedConfigRegistry registry;
  private final CcdCallbackExecutor executor;
  private final ObjectMapper mapper;
  private final ObjectMapper filteredMapper;
  private final ObjectProvider<CdamAttachService> cdamAttachService;

  LegacyCallbackSubmissionHandler(ResolvedConfigRegistry registry,
                                  CcdCallbackExecutor executor,
                                  @Qualifier(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER)
                                  ObjectMapper mapper,
                                  ObjectProvider<CdamAttachService> cdamAttachService) {
    this.registry = registry;
    this.executor = executor;
    this.mapper = mapper;
    this.filteredMapper = mapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
    this.cdamAttachService = cdamAttachService;
  }

  @Override
  public CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event, IdamService.User user) {
    String authorisation = user.authToken();
    log.info("[legacy] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    CdamAttachService service = cdamAttachService.getIfAvailable();
    JsonNode preCallbackData = service == null ? null : preCallbackDocumentBaselineData(event);
    var outcome = prepareLegacySubmit(event);

    var submitResponse = outcome.response();
    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

    attachNewCdamDocuments(event, authorisation, service, preCallbackData);

    boolean runSubmitted = outcome.runSubmittedCallback();

    var errors = submitResponse.getErrors();
    var warnings = submitResponse.getWarnings();
    JsonNode dataSnapshot = snapshotWithFilteredFields(event);
    var state = Optional.ofNullable(event.getCaseDetails().getState());
    var securityClassification = Optional.ofNullable(event.getCaseDetails().getSecurityClassification())
        .map(SecurityClassification::name)
        .map(Classification::valueOf);

    return new CaseSubmissionHandlerResult(
        Optional.ofNullable(dataSnapshot),
        state,
        securityClassification,
        Optional.ofNullable(outcome.eventMetadata()),
        Optional.ofNullable(outcome.significantItem()),
        () -> {
          var builder = SubmitResponse.builder()
              .errors(errors)
              .warnings(warnings);

          if (runSubmitted) {
            SubmittedCallbackOutcome submittedOutcome = runSubmittedCallback(event);
            submittedOutcome.response().ifPresent(submittedResponse -> {
              builder.confirmationHeader(submittedResponse.getConfirmationHeader());
              builder.confirmationBody(submittedResponse.getConfirmationBody());
            });
            submittedOutcome.failureMessage().ifPresent(builder::callbackErrorMessage);
          }
          securityClassification.ifPresent(builder::caseSecurityClassification);
          return builder.build();
        });
  }

  private LegacySubmitOutcome prepareLegacySubmit(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    var response = new DecentralisedSubmitEventResponse();
    EventMetadata eventMetadata = null;
    SignificantItem significantItem = null;

    if (eventConfig.getAboutToSubmitCallback() != null) {
      CallbackRequest request = buildCallbackRequest(event);
      AboutToStartOrSubmitResponse callbackResponse = executor.aboutToSubmit(request);
      eventMetadata = callbackResponse.getEventMetadata();
      significantItem = callbackResponse.getSignificantItem();

      // Mirror CCD: a callback that returns no data leaves the event's data as it was.
      if (callbackResponse.getData() != null) {
        event.getCaseDetails().setData(mapper.convertValue(callbackResponse.getData(), JSON_NODE_MAP));
      }

      if (callbackResponse.getState() != null) {
        event.getCaseDetails().setState(callbackResponse.getState().toString());
      }
      if (callbackResponse.getSecurityClassification() != null) {
        event.getCaseDetails().setSecurityClassification(
            SecurityClassification.valueOf(callbackResponse.getSecurityClassification())
        );
      }

      response.setErrors(callbackErrors(callbackResponse));
      response.setWarnings(callbackResponse.getWarnings());
    }

    boolean hasSubmitted = eventConfig.getSubmittedCallback() != null;
    return new LegacySubmitOutcome(response, eventMetadata, significantItem, hasSubmitted);
  }

  /**
   * CCD rejects an about to submit response with a non-empty error_message_override even when it has no errors.
   * The decentralised submit response has no override field, so the override is returned to CCD as an error.
   */
  private static List<String> callbackErrors(AboutToStartOrSubmitResponse<?, ?> callbackResponse) {
    String override = callbackResponse.getErrorMessageOverride();
    if (override == null || override.isEmpty()) {
      return callbackResponse.getErrors();
    }
    List<String> errors = new ArrayList<>();
    errors.add(override);
    if (callbackResponse.getErrors() != null) {
      errors.addAll(callbackResponse.getErrors());
    }
    return errors;
  }

  private SubmittedCallbackOutcome runSubmittedCallback(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (eventConfig.getSubmittedCallback() == null) {
      return new SubmittedCallbackOutcome(Optional.empty(), Optional.empty());
    }

    CallbackRequest request = buildCallbackRequest(event);
    // Mirror CCD behaviour: any non-empty retry config equates to 3 attempts (initial call + 2 retries)
    // CCD ignores the actual numbers specified in the ccd definition!
    // https://github.com/hmcts/ccd-data-store-api/blob/master/src/main/java/uk/gov/hmcts/ccd/domain/service/callbacks/CallbackService.java#L41-L63
    var retriesConfig = eventConfig.getRetries().get(Webhook.Submitted);
    int retries = retriesConfig == null || retriesConfig.isEmpty() ? 1 : 3;
    Exception terminalFailure = null;

    for (int attempt = 0; attempt < retries; attempt++) {
      try {
        SubmittedCallbackResponse submitted = executor.submitted(request);
        log.debug("Submitted callback returned header={} body={}",
            submitted != null ? submitted.getConfirmationHeader() : null,
            submitted != null ? submitted.getConfirmationBody() : null);
        return new SubmittedCallbackOutcome(Optional.ofNullable(submitted), Optional.empty());
      } catch (Exception ex) {
        terminalFailure = ex;
        log.warn("Unsuccessful submitted callback attempt={} caseType={} eventId={}",
            attempt + 1, caseType, eventId);
      }
    }

    log.error("Submitted callback failed after all attempts for caseType={} eventId={}",
        caseType, eventId, terminalFailure);
    return new SubmittedCallbackOutcome(
        Optional.empty(),
        Optional.of("Submitted callback failed after " + retries + " attempt(s)")
    );
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

  private record LegacySubmitOutcome(DecentralisedSubmitEventResponse response,
                                     EventMetadata eventMetadata,
                                     SignificantItem significantItem,
                                     boolean runSubmittedCallback) {}

  private record SubmittedCallbackOutcome(Optional<SubmittedCallbackResponse> response,
                                          Optional<String> failureMessage) {}

  private void attachNewCdamDocuments(DecentralisedCaseEvent event,
                                      String authorisation,
                                      CdamAttachService service,
                                      JsonNode preCallbackData) {
    if (service == null) {
      return;
    }

    JsonNode postCallbackData = mapper.valueToTree(event.getCaseDetails().getData());
    JsonNode strippedData = service.attachNewDocumentsAndStripHashes(
        authorisation,
        event,
        preCallbackData,
        postCallbackData
    );
    event.getCaseDetails().setData(mapper.convertValue(strippedData, JSON_NODE_MAP));
  }

  private JsonNode preCallbackDocumentBaselineData(DecentralisedCaseEvent event) {
    ArrayNode baseline = mapper.createArrayNode();
    // Match CCD data-store's document attach baseline: database data plus event input are both pre-callback documents.
    // Only documents first returned by the about-to-submit callback should be attached by the SDK.
    if (event.getCaseDetailsBefore() != null) {
      baseline.add(mapper.valueToTree(event.getCaseDetailsBefore().getData()));
    }
    baseline.add(mapper.valueToTree(event.getCaseDetails().getData()));
    return baseline;
  }

  @SneakyThrows
  private JsonNode snapshotWithFilteredFields(DecentralisedCaseEvent event) {
    Map<String, JsonNode> currentData = event.getCaseDetails().getData();

    var caseType = event.getEventDetails().getCaseType();
    var caseClass = registry.getRequired(caseType).getCaseClass();

    Object domainCaseData = mapper.convertValue(currentData, caseClass);

    String filteredJson = filteredMapper.writeValueAsString(domainCaseData);
    return mapper.readTree(filteredJson);
  }
}
