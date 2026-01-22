package uk.gov.hmcts.civil.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Getter
@RequiredArgsConstructor
public enum ClaimType {

    @CCD(label = "Personal injury")
    PERSONAL_INJURY("Personal injury"),

    @CCD(label = "Clinical negligence")
    CLINICAL_NEGLIGENCE("Clinical negligence"),

    @CCD(label = "Professional negligence")
    PROFESSIONAL_NEGLIGENCE("Professional negligence"),

    @CCD(label = "Breach of contract")
    BREACH_OF_CONTRACT("Breach of contract"),

    @CCD(label = "Consumer")
    CONSUMER("Consumer"),

    @CCD(label = "Consumer credit")
    CONSUMER_CREDIT("Consumer credit"),

    @CCD(label = "Other")
    OTHER("Other");

    @JsonValue
    private final String displayValue;
}

