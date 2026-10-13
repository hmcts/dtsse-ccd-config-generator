package uk.gov.hmcts.divorce.simplecase.model;

import com.fasterxml.jackson.annotation.JsonValue;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public enum SimpleCaseState {
    DRAFT,
    CREATED,
    @CCD(description = "Follow-up details recorded")
    FOLLOW_UP,
    PendingDisposal;

    @JsonValue
    public String getId() {
        return name();
    }
}
