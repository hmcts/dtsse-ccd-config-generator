package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

/**
 * Legacy submission flow that still relies on the "about to submit" and
 * "submitted" callbacks. Extracted so we can contain legacy-specific behaviour
 * outside the controller and share the top-level orchestration.
 */
@Slf4j
@Component
class LegacyCallbackSubmissionHandler implements CaseSubmissionHandler {

  private final ResolvedConfigRegistry registry;
  private final DefinitionRegistry definitionRegistry;
  private final List<LegacyCallbackDispatcher> dispatchers;
  private final ObjectMapper mapper;
  private final ObjectMapper filteredMapper;

  LegacyCallbackSubmissionHandler(ResolvedConfigRegistry registry,
                                  DefinitionRegistry definitionRegistry,
                                  List<LegacyCallbackDispatcher> dispatchers,
                                  ObjectMapper mapper) {
    this.registry = registry;
    this.definitionRegistry = definitionRegistry;
    this.dispatchers = dispatchers.stream()
        .sorted(AnnotationAwareOrderComparator.INSTANCE)
        .toList();
    this.mapper = mapper;
    this.filteredMapper = mapper.copy().setAnnotationIntrospector(new FilterExternalFieldsInspector());
  }

  @Override
  public CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event, String authorisation) {
    log.info("[legacy] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    Optional<LegacyCallback> callback = resolveCallback(event);
    var outcome = prepareLegacySubmit(event, callback, authorisation);

    var submitResponse = outcome.response();
    if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
      throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
    }

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
        () -> {
          var builder = SubmitResponse.builder()
              .errors(errors)
              .warnings(warnings);

          LegacySubmittedCallbackResponse submittedResponse = callback
              .flatMap(resolved -> runSubmittedCallback(resolved, event, authorisation))
              .orElse(null);

          if (submittedResponse != null) {
            builder.confirmationHeader(submittedResponse.confirmationHeader());
            builder.confirmationBody(submittedResponse.confirmationBody());
          }
          securityClassification.ifPresent(builder::caseSecurityClassification);
          return builder.build();
        });
  }

  private LegacySubmitOutcome prepareLegacySubmit(DecentralisedCaseEvent event,
                                                  Optional<LegacyCallback> callback,
                                                  String authorisation) {
    var response = new DecentralisedSubmitEventResponse();

    callback.flatMap(resolved -> {
      CallbackRequest request = buildCallbackRequest(event);
      return resolved.aboutToSubmit(request, authorisation);
    }).ifPresent(callbackResponse -> {
      Map<String, JsonNode> normalisedData = callbackResponse.data() == null
          ? Map.of()
          : callbackResponse.data();
      event.getCaseDetails().setData(normalisedData);

      if (callbackResponse.state() != null) {
        event.getCaseDetails().setState(callbackResponse.state());
      }
      if (callbackResponse.securityClassification() != null) {
        event.getCaseDetails().setSecurityClassification(callbackResponse.securityClassification());
      }

      response.setErrors(callbackResponse.errors());
      response.setWarnings(callbackResponse.warnings());
    });

    return new LegacySubmitOutcome(response);
  }

  private Optional<LegacySubmittedCallbackResponse> runSubmittedCallback(LegacyCallback callback,
                                                                        DecentralisedCaseEvent event,
                                                                        String authorisation) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();

    CallbackRequest request = buildCallbackRequest(event);
    int attempts = callback.submittedAttempts();

    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        Optional<LegacySubmittedCallbackResponse> submitted = callback.submitted(request, authorisation);
        if (submitted.isEmpty()) {
          return Optional.empty();
        }
        log.debug("Submitted callback returned header={} body={}",
            submitted.get().confirmationHeader(),
            submitted.get().confirmationBody());
        return submitted;
      } catch (Exception ex) {
        log.warn("Unsuccessful submitted callback for caseType={} eventId={}", caseType, eventId, ex);
      }
    }

    return Optional.of(new LegacySubmittedCallbackResponse(null, null));
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

  private Optional<LegacyCallback> resolveCallback(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();

    boolean knownEvent = registry.find(caseType)
        .map(config -> config.getEvents().containsKey(eventId))
        .orElse(false)
        || definitionRegistry.findEvent(caseType, eventId).isPresent();

    if (!knownEvent) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "No event %s defined for case type %s".formatted(eventId, caseType)
      );
    }

    return dispatchers.stream()
        .map(dispatcher -> dispatcher.resolve(caseType, eventId))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private record LegacySubmitOutcome(DecentralisedSubmitEventResponse response) {}

  @SneakyThrows
  private JsonNode snapshotWithFilteredFields(DecentralisedCaseEvent event) {
    Map<String, JsonNode> currentData = event.getCaseDetails().getData();

    var caseType = event.getEventDetails().getCaseType();
    var config = registry.find(caseType);
    if (config.isEmpty()) {
      return mapper.valueToTree(currentData);
    }

    Object domainCaseData = mapper.convertValue(currentData, config.get().getCaseClass());

    String filteredJson = filteredMapper.writeValueAsString(domainCaseData);
    return mapper.readTree(filteredJson);
  }
}
