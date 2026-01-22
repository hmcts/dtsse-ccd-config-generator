package uk.gov.hmcts.divorce.divorcecase.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalRepresentative {

    @CCD(
        label = "Reference",
        hint = "Legal representative's reference"
    )
    private String reference;

    @CCD(
        label = "Organisation name",
        hint = "Name of the legal representative organisation"
    )
    private String organisationName;

    @CCD(
        label = "Email address",
        hint = "Email address for notifications",
        typeOverride = FieldType.Email
    )
    private String emailAddress;

    @CCD(
        label = "Service address",
        hint = "Address for postal correspondence"
    )
    private AddressUK serviceAddress;
}
