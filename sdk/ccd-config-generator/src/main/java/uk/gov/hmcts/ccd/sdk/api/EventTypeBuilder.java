package uk.gov.hmcts.ccd.sdk.api;

import java.util.EnumSet;

public interface EventTypeBuilder<T, R extends HasRole, S> {

  Event.EventBuilder<T, R, S> forState(S state);

  Event.EventBuilder<T, R, S> initialState(S state);

  Event.EventBuilder<T, R, S> forStateTransition(S from, S to);

  Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, S to);

  Event.EventBuilder<T, R, S> forStateTransition(S from, EnumSet to);

  Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, EnumSet to);

  Event.EventBuilder<T, R, S> forAllStates();

  Event.EventBuilder<T, R, S> forStates(EnumSet states);

  Event.EventBuilder<T, R, S> forStates(S... states);
}
