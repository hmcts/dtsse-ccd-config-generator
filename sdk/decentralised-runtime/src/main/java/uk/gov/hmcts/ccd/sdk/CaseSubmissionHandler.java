package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.function.Supplier;

import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
interface CaseSubmissionHandler {

  Supplier<SubmitResponse> apply(
      DecentralisedCaseEvent event);

}
