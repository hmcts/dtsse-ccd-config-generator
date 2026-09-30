package uk.gov.hmcts.ccd.sdk.api.external;

/**
 * What an external event's start handler is given when a frontend starts the event: the case it
 * is starting on, who is starting it, and anything the frontend said about it in its client context,
 * such as which record the user chose. The handler loads whatever it needs to send the frontend.
 */
public record ExternalStartRequest(long caseReference, ExternalUser user, ClientContext clientContext) {

  /** A start with no client context. */
  public ExternalStartRequest(long caseReference, ExternalUser user) {
    this(caseReference, user, ClientContext.none());
  }
}
