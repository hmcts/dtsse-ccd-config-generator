package uk.gov.hmcts.divorce.simplecase.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SimpleCaseState {
    DRAFT,
    CREATED,
    FOLLOW_UP,
    SUBMITTED,
    DELETE;

    @JsonValue
    public String getId() {
        return name();
    }
}
