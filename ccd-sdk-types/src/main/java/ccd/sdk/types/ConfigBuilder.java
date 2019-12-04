package ccd.sdk.types;

public interface ConfigBuilder {
    FieldBuilder caseField(String standardDirectionsDocument, FieldType document);
    Event.EventBuilder event(String id);

    void caseType(String caseType);
}
