package ccd.sdk.types;

public interface ConfigBuilder<T> {
    EventTypeBuilder<T> event(String id);

    void caseType(String caseType);
    // TODO: require enums as additional generic type params.
    void grant(String state, String permissions, String role);
    void blacklist(String state, String... role);
    void explicitState(String eventId, String role, String crud);
}
