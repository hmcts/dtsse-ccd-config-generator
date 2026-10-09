package uk.gov.hmcts.ccd.sdk.api;

import org.springframework.util.MultiValueMap;

// TODO: caseReference is nullable currently.
// We should have a separate start event for create case start events that do not have a case reference.
public record EventPayload<T, S>(Long caseReference,
                                 T caseData,
                                 MultiValueMap<String, String> urlParams,
                                 AfterCommit afterCommit) {

  /** A payload nothing commits after, as a start handler is given. */
  public EventPayload(Long caseReference, T caseData, MultiValueMap<String, String> urlParams) {
    this(caseReference, caseData, urlParams, AfterCommit.none());
  }

  /** Runs the action once the event has committed, before its response is returned. Submit handlers only. */
  public void afterCommit(Runnable action) {
    afterCommit.add(action);
  }
}
