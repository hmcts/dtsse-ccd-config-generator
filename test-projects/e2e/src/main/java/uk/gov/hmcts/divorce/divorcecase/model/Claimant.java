package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Claimant {

    @CCD(
        label = "Claimant type",
        hint = "Type of claimant",
        typeOverride = FieldType.FixedRadioList
    )
    private PartyType type;

    @CCD(
        label = "Title",
        hint = "Title of the claimant"
    )
    private String title;

    @CCD(
        label = "First name",
        hint = "First name of the claimant"
    )
    private String firstName;

    @CCD(
        label = "Last name",
        hint = "Last name of the claimant"
    )
    private String lastName;

    @CCD(
        label = "Date of birth",
        hint = "Date of birth of the claimant"
    )
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    @CCD(
        label = "Email",
        hint = "Email address of the claimant",
        typeOverride = FieldType.Email
    )
    private String email;

    @CCD(
        label = "Phone",
        hint = "Phone number of the claimant",
        typeOverride = FieldType.PhoneUK
    )
    private String phone;

    @CCD(
        label = "Address",
        hint = "Address of the claimant"
    )
    private AddressUK address;

    @CCD(
        label = "Is the claimant a child",
        hint = "Is the claimant a child i.e. under the age of 18?"
    )
    private uk.gov.hmcts.ccd.sdk.type.YesOrNo isChild;
}

