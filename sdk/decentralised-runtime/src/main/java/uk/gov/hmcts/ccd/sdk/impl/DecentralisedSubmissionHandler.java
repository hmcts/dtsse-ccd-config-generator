package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

/**
 * Submission flow that relies on the decentralised submit handler instead of the
 * legacy about-to-submit callback sequence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DecentralisedSubmissionHandler implements CaseSubmissionHandler {

  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;

  @Override
  public CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event, String authorisation) {
    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = prepareSubmitHandler(event);

    if (outcome.getErrors() != null && !outcome.getErrors().isEmpty()) {
      throw new CallbackValidationException(outcome.getErrors(), outcome.getWarnings());
    }

    var state = Optional.ofNullable(outcome.getState()).map(Object::toString);
    var securityClassification = Optional.ofNullable(outcome.getCaseSecurityClassification());

    return new CaseSubmissionHandlerResult(
        Optional.empty(),
        state,
        securityClassification,
        Optional.ofNullable(outcome.getEventMetadata()),
        Optional.ofNullable(outcome.getSignificantItem()),
        () -> outcome);
  }

  private SubmitResponse<?> prepareSubmitHandler(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (!eventConfig.hasSubmitHandler()) {
      throw new IllegalStateException("Submit handler not configured for event %s".formatted(eventId));
    }

    Map<String, JsonNode> data = event.getCaseDetails().getData();
    long caseRef = event.getCaseDetails().getReference();

    // TODO: revisit when CCD resumes sending query params; referer header is absent at the moment.
    var urlParams = new LinkedMultiValueMap<String, String>();

    // An external event's handler is given its payload, never the case, so the case is not read.
    if (eventConfig.isExternal()) {
      JsonNode payloadField = data == null ? null : data.get(DecentralisedConfigBuilder.PAYLOAD_FIELD);
      Object submitted = readPayload(eventId, payloadField, eventConfig.getSubmitType());
      return eventConfig.submit(new EventPayload(caseRef, null, urlParams), submitted);
    }

    Object domainCaseData = mapper.convertValue(data, registry.getRequired(caseType).getCaseClass());
    return eventConfig.submit(new EventPayload(caseRef, domainCaseData, urlParams), null);
  }

  /**
   * The payload field holds the payload as a JSON string. Anything else is the frontend's mistake,
   * rejected as a handler rejects a submission so the frontend gets a 422 with the reason.
   */
  private Object readPayload(String eventId, JsonNode field, Class<?> payloadType) {
    String missing = "Event %s requires a JSON string payload in %s"
        .formatted(eventId, DecentralisedConfigBuilder.PAYLOAD_FIELD);
    if (field == null || !field.isTextual() || field.asText().isBlank()) {
      throw unreadable(missing);
    }
    try {
      JsonNode tree = mapper.readTree(field.asText());
      // Checked before binding, which would read null as 0 or false for a primitive payload type.
      if (tree.isNull()) {
        throw unreadable(missing);
      }
      return mapper.treeToValue(tree, payloadType);
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw unreadable("Event %s payload is not a valid %s".formatted(eventId, payloadType.getSimpleName()));
    }
  }

  private static CallbackValidationException unreadable(String error) {
    return new CallbackValidationException(List.of(error), List.of());
  }
}
