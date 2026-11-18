package uk.gov.hmcts.ccd.sdk.api;

import java.util.Objects;
import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.external.ClientContext;

// TODO: caseReference is nullable currently.
// We should have a separate start event for create case start events that do not have a case reference.
// clientContext is what the frontend said when it started the event, which only a start is told.
public record EventPayload<T, S>(Long caseReference,
                                 T caseData,
                                 MultiValueMap<String, String> urlParams,
                                 AfterCommit afterCommitActions,
                                 ClientContext clientContext) {

  public EventPayload {
    clientContext = Objects.requireNonNullElse(clientContext, ClientContext.none());
  }

  /** A payload whose registered after-commit work is discarded, as a start handler's is. */
  public EventPayload(Long caseReference, T caseData, MultiValueMap<String, String> urlParams) {
    this(caseReference, caseData, urlParams, new AfterCommit(), ClientContext.none());
  }

  public EventPayload(Long caseReference, T caseData, MultiValueMap<String, String> urlParams,
                      AfterCommit afterCommitActions) {
    this(caseReference, caseData, urlParams, afterCommitActions, ClientContext.none());
  }

  /** Runs the action once the event has committed, before its response is returned. */
  public void afterCommit(Runnable action) {
    afterCommitActions.add(action);
  }
}
