package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
interface CaseSubmissionHandler {

  CaseSubmissionOutcome apply(DecentralisedCaseEvent event,
                              IdamService.User user,
                              UUID idempotencyKey);

  CaseSubmissionOutcome alreadyProcessed(DecentralisedCaseEvent event,
                                         UUID idempotencyKey);
}
