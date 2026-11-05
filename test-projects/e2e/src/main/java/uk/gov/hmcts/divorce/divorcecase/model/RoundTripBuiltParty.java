package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * A party that Jackson builds through a creator rather than setters, as a Lombok
 * {@code @Builder @Jacksonized} class is. {@link CaseData} holds it behind a prefixed
 * {@code @JsonUnwrapped} so round-trip tests prove nested SDK types survive on that path too.
 */
@Getter
@Builder
@Jacksonized
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class RoundTripBuiltParty {

    @CCD(label = "Built party name")
    private final String name;

    @CCD(label = "Built party document")
    private final Document document;

    @CCD(label = "Built party address")
    private final AddressUK addressUk;

    @CCD(label = "Built party organisation policy")
    private final OrganisationPolicy<UserRole> organisationPolicy;

    @CCD(label = "Built party dynamic list")
    private final DynamicList dynamicList;

    @CCD(label = "Built party confirmed")
    private final YesOrNo confirmed;
}
