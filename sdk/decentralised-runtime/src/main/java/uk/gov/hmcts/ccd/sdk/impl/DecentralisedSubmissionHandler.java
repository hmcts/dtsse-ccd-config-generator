package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
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
  private final HttpServletRequest httpRequest;

  @Override
  public CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event) {
    log.info("[submit-handler] Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());

    var outcome = prepareSubmitHandler(event);

    if (outcome.getErrors() != null && !outcome.getErrors().isEmpty()) {
      throw new CallbackValidationException(outcome.getErrors(), outcome.getWarnings());
    }

    var state = Optional.ofNullable(outcome.getState()).map(Object::toString);
    var securityClassification = Optional.ofNullable(outcome.getCaseSecurityClassification());

    return new CaseSubmissionHandlerResult(Optional.empty(), state, securityClassification, () -> outcome);
  }

  private SubmitResponse<?> prepareSubmitHandler(DecentralisedCaseEvent event) {
    String caseType = event.getEventDetails().getCaseType();
    String eventId = event.getEventDetails().getEventId();
    Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseType, eventId);

    if (eventConfig.getSubmitHandler() == null) {
      throw new IllegalStateException("Submit handler not configured for event %s".formatted(eventId));
    }

    var config = registry.getRequired(caseType);

    Object domainCaseData = mapper.convertValue(
        event.getCaseDetails().getData(),
        config.getCaseClass()
    );
    long caseRef = event.getCaseDetails().getReference();

    var urlParams = extractQueryParams();

    return eventConfig.getSubmitHandler()
        .submit(new uk.gov.hmcts.ccd.sdk.api.EventPayload(caseRef, domainCaseData, urlParams));
  }

  private MultiValueMap<String, String> extractQueryParams() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    httpRequest.getParameterMap()
        .forEach((key, values) -> params.put(key, Arrays.asList(values)));
    return params;
  }
}
