package ccd.sdk.types;

import java.util.function.Consumer;

public class EventTypeBuilder<T> {
    private final Event.EventBuilder<T, Role> builder;
    private final Consumer<String> callback;

    public EventTypeBuilder(Event.EventBuilder<T, Role> builder, Consumer<String> callback) {
        this.builder = builder;
        this.callback = callback;
    }

    public Event.EventBuilder<T, Role> forState(String state) {
        callback.accept(state);
        return builder.forStates(state);
    }

    public Event.EventBuilder<T, Role> initialState(String state) {
        callback.accept(state);
        return builder.preState(null).postState(state);
    }

    public Event.EventBuilder<T, Role> forStateTransition(String from, String to) {
        callback.accept(to);
        return builder.preState(from).postState(to);
    }

    public Event.EventBuilder<T, Role> forAllStates() {
        callback.accept("*");
        return builder.forState("*");
    }
}
