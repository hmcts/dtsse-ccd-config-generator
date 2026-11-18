package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;

/**
 * Nested inside {@link RoundTripParty} behind a second {@code @JsonUnwrapped} prefix, matching
 * how services model an applicant's solicitor.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@Builder
public class RoundTripSolicitor {

    @CCD(label = "Solicitor name")
    private String name;

    @CCD(label = "Solicitor organisation policy")
    private OrganisationPolicy<UserRole> organisationPolicy;

    @CCD(label = "Solicitor address")
    private AddressUK address;
}
