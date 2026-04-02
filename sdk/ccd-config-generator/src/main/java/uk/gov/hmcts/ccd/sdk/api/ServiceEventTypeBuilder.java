package uk.gov.hmcts.ccd.sdk.api;

import java.util.EnumSet;

/**
 * Builder for selecting the state(s) of a service event.
 * Returns a {@link ServiceEventBuilder} which restricts the API to methods
 * that apply to service events (no pages, fields, or XUI display options).
 */
public interface ServiceEventTypeBuilder<T, R extends HasRole, S> {

  ServiceEventBuilder<T, R, S> forState(S state);

  ServiceEventBuilder<T, R, S> initialState(S state);

  ServiceEventBuilder<T, R, S> forStateTransition(S from, S to);

  ServiceEventBuilder<T, R, S> forStateTransition(EnumSet from, S to);

  ServiceEventBuilder<T, R, S> forStateTransition(S from, EnumSet to);

  ServiceEventBuilder<T, R, S> forStateTransition(EnumSet from, EnumSet to);

  ServiceEventBuilder<T, R, S> forAllStates();

  ServiceEventBuilder<T, R, S> forStates(EnumSet states);

  ServiceEventBuilder<T, R, S> forStates(S... states);
}
