package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Supplier;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
interface CaseSubmissionHandler {

  CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event);

  record CaseSubmissionHandlerResult(
      Optional<JsonNode> dataUpdate,
      Optional<String> state,
      Optional<uk.gov.hmcts.reform.ccd.client.model.Classification> securityClassification,
      Supplier<SubmitResponse<?>> responseSupplier) {
  }
}
