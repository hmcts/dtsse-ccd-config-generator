package ccd.sdk.types;

public interface ConfigBuilder<T, S, R extends Role> {
    EventTypeBuilder<T, S> event(String id);

    void caseType(String caseType);
    // TODO: require enums as additional generic type params.
    void grant(S state, String permissions, R role);
    void blacklist(S state, R... role);
    void explicitState(String eventId, R role, String crud);
    void prefix(S state, String prefix);
}
