package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
public interface CaseSubmissionHandler {

  DecentralisedSubmitEventResponse handle(DecentralisedCaseEvent event,
                                          IdamService.User user,
                                          String idempotencyKey);
}
