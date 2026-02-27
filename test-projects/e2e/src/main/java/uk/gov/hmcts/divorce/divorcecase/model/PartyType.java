package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Getter
@RequiredArgsConstructor
public enum PartyType {

    @CCD(label = "Individual")
    INDIVIDUAL("Individual"),

    @CCD(label = "Company")
    COMPANY("Company"),

    @CCD(label = "Organisation")
    ORGANISATION("Organisation"),

    @CCD(label = "Sole trader")
    SOLE_TRADER("Sole trader");

    @JsonValue
    private final String displayValue;
}

