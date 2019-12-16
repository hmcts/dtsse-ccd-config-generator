package ccd.sdk.types;

public interface ConfigBuilder<T, R extends Role> {
    EventTypeBuilder<T> event(String id);

    void caseType(String caseType);
    // TODO: require enums as additional generic type params.
    void grant(String state, String permissions, R role);
    void blacklist(String state, R... role);
    void explicitState(String eventId, R role, String crud);
    void prefix(String state, String prefix);
}
