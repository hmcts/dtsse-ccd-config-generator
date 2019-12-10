package ccd.sdk.types;

public interface ConfigBuilder<T> {
    EventTypeBuilder<T> event(String id);

    void caseType(String caseType);
}
