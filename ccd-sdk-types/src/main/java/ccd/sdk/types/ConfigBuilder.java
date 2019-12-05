package ccd.sdk.types;

public interface ConfigBuilder<T> {
    Event.EventBuilder<T> event(String id);

    void caseType(String caseType);
}
