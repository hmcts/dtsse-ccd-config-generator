package uk.gov.hmcts.ccd.sdk.api.external;

import uk.gov.hmcts.ccd.sdk.api.AfterCommit;

/**
 * What an external event's submit handler is given: the case, the payload the frontend sent, and
 * who sent it.
 */
public record ExternalSubmitRequest<I>(long caseReference, I payload, ExternalUser user,
                                      AfterCommit afterCommitActions) {

  /** Runs the action once the event has committed, before its response is returned. */
  public void afterCommit(Runnable action) {
    afterCommitActions.add(action);
  }
}
