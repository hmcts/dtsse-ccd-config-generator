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
public class Defendant {

    @CCD(
        label = "Defendant type",
        hint = "Type of defendant",
        typeOverride = FieldType.FixedRadioList,
        typeParameterOverride = "PartyType"
    )
    private PartyType type;

    @CCD(
        label = "Title",
        hint = "Title of the defendant"
    )
    private String title;

    @CCD(
        label = "First name",
        hint = "First name of the defendant"
    )
    private String firstName;

    @CCD(
        label = "Last name",
        hint = "Last name of the defendant"
    )
    private String lastName;

    @CCD(
        label = "Email",
        hint = "Email address of the defendant",
        typeOverride = FieldType.Email
    )
    private String email;

    @CCD(
        label = "Phone",
        hint = "Phone number of the defendant",
        typeOverride = FieldType.PhoneUK
    )
    private String phone;

    @CCD(
        label = "Address",
        hint = "Address of the defendant"
    )
    private AddressUK address;
}
