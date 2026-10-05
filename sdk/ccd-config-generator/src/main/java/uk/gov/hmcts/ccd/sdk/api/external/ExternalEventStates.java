package uk.gov.hmcts.ccd.sdk.api.external;

import uk.gov.hmcts.ccd.sdk.api.HasRole;

/** The states in which an external event can be started on a case. */
public interface ExternalEventStates<T, R extends HasRole, S, O, I> {

  ExternalEventBuilder<T, R, S, O, I> forState(S state);

  ExternalEventBuilder<T, R, S, O, I> forStates(S... states);

  ExternalEventBuilder<T, R, S, O, I> forAllStates();
}
