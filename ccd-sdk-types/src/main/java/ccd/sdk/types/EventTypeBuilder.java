package ccd.sdk.types;

public class EventTypeBuilder<T> {
    private final Event.EventBuilder<T> builder;

    public EventTypeBuilder(Event.EventBuilder<T> builder) {
        this.builder = builder;
    }

    public Event.EventBuilder<T> forState(String state) {
        return builder.forStates(state);
    }

    public Event.EventBuilder<T> forStates(String... states) {
        return builder.forStates(states);
    }

    public Event.EventBuilder<T> initialState(String state) {
        return builder.preState(null).postState(state);
    }

    public Event.EventBuilder<T> forStateTransition(String from, String to) {
        return builder.preState(from).postState(to);
    }

    public Event.EventBuilder<T> forAllStates() {
        return builder.forState("*");
    }
}
