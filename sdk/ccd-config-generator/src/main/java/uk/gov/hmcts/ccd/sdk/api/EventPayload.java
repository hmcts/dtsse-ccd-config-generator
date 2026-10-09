package uk.gov.hmcts.ccd.sdk.api;

import org.springframework.util.MultiValueMap;

// TODO: caseReference is nullable currently.
// We should have a separate start event for create case start events that do not have a case reference.
public record EventPayload<T, S>(Long caseReference,
                                 T caseData,
                                 MultiValueMap<String, String> urlParams,
                                 AfterCommit afterCommit) {

  /** As a start handler is given: work registered after it never runs. */
  public EventPayload(Long caseReference, T caseData, MultiValueMap<String, String> urlParams) {
    this(caseReference, caseData, urlParams, new AfterCommit());
  }

  /** Runs the action once the event has committed, before its response is returned. */
  public void afterCommit(Runnable action) {
    afterCommit.add(action);
  }
}
