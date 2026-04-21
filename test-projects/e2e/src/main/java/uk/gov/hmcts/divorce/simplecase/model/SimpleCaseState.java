package uk.gov.hmcts.divorce.simplecase.model;

import com.fasterxml.jackson.annotation.JsonValue;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.divorce.divorcecase.model.access.DefaultStateAccess;

public enum SimpleCaseState {

    DRAFT,

    @CCD(access = {DefaultStateAccess.class})
    CREATED,

    @CCD(access = {DefaultStateAccess.class})
    FOLLOW_UP;

    @JsonValue
    public String getId() {
        return name();
    }
}
