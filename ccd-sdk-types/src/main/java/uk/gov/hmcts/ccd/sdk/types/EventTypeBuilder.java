package uk.gov.hmcts.ccd.sdk.types;

import java.util.function.Consumer;

public class EventTypeBuilder<T, S> {
    private final Event.EventBuilder<T, Role, S> builder;
    private final Consumer<String> callback;

    public EventTypeBuilder(Event.EventBuilder<T, Role, S> builder, Consumer<String> callback) {
        this.builder = builder;
        this.callback = callback;
    }

    public Event.EventBuilder<T, Role, S> forState(S state) {
        callback.accept(state.toString());
        return builder.forStates(state);
    }

    public Event.EventBuilder<T, Role, S> initialState(S state) {
        callback.accept(state.toString());
        return builder.preState(null).postState(state);
    }

    public Event.EventBuilder<T, Role, S> forStateTransition(S from, S to) {
        callback.accept(to.toString());
        return builder.preState(from).postState(to);
    }

    public Event.EventBuilder<T, Role, S> forAllStates() {
        callback.accept("*");
        return builder.forState("*");
    }
}
