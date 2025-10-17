package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
interface CaseSubmissionHandler {

  java.util.function.Supplier<uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse> apply(
      DecentralisedCaseEvent event);
}
