package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;
import uk.gov.hmcts.divorce.divorcecase.model.access.AcaSystemUserAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerWithCAAAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.DefaultAccess;

import static uk.gov.hmcts.ccd.sdk.type.FieldType.Email;
import static uk.gov.hmcts.ccd.sdk.type.FieldType.TextArea;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@Builder
public class Applicant {

    @CCD(label = "First name")
    private String firstName;

    @CCD(
        label = "Middle name(s)",
        hint = "If they have a middle name then you must enter it to avoid amendments later."
    )
    private String middleName;

    @CCD(
        label = "Last name",
        access = {CaseworkerWithCAAAccess.class}
    )
    private String lastName;

    @CCD(
        label = "Confirm your full name"
    )
    private YesOrNo confirmFullName;

    @CCD(
        label = "Email address",
        typeOverride = Email
    )
    private String email;

    @CCD(
        label = "They have agreed to receive notifications and be served (delivered) court documents by email"
    )
    private YesOrNo agreedToReceiveEmails;

    @CCD(
        label = "Has the applicant confirmed the receipt?"
    )
    private YesOrNo confirmReceipt;

    @CCD(
        label = "Is the language preference Welsh?",
        hint = "Select \"No\" for English or \"Yes\" for bilingual"
    )
    private YesOrNo languagePreferenceWelsh;

    @CCD(
        label = "Did they change their last name when they got married?"
    )
    private YesOrNo lastNameChangedWhenMarried;

    @CCD(
        label = "Details of how they changed their last name when they got married",
        typeOverride = TextArea
    )
    private String lastNameChangedWhenMarriedOtherDetails;

    @CCD(
        label = "Have they changed their name since they got married?"
    )
    private YesOrNo nameDifferentToMarriageCertificate;

    @CCD(
        label = "Details of how they changed their name since they got married",
        typeOverride = TextArea
    )
    private String nameDifferentToMarriageCertificateOtherDetails;

    @CCD(
        label = "Details of how they changed their name",
        typeOverride = TextArea
    )
    private String nameChangedHowOtherDetails;

    /* Second address field to allow solicitors to enter applicant addresses when creating applications
     * and view non-confidential addresses for solicitor service. We do not give solicitors read access to the
     * primary "address" field as it can contain a confidential address. */
    @CCD(label = "Non-Confidential Address")
    private AddressGlobalUK nonConfidentialAddress;

    @CCD(label = "Is this an international address?")
    private YesOrNo addressOverseas;

    @CCD(
        label = "Is represented by a solicitor?",
        access = {AcaSystemUserAccess.class}
    )
    private YesOrNo solicitorRepresented;


    @CCD(
        label = "Phone number",
        regex = "^[0-9 +().-]{9,}$"
    )
    private String phoneNumber;


    @CCD(
        label = "Is the Applicant currently resident in a refuge?"
    )
    private YesOrNo inRefuge;


    @CCD(
        label = "Does the applicant wish to apply for a financial order?"
    )
    private YesOrNo financialOrder;

    @CCD(
        label = "Applicant has used the Welsh translation on submission"
    )
    private YesOrNo usedWelshTranslationOnSubmission;

    @CCD(
        label = "Provide details of the other legal proceedings",
        hint = "Provide as much information as possible, such as the case number(s); "
            + "the names of the people involved and if the proceedings are ongoing or if they’ve finished.",
        typeOverride = TextArea
    )
    private String legalProceedingsDetails;

    @CCD(
        label = "Provide details of the other legal proceedings(Translated)",
        typeOverride = TextArea
    )
    private String legalProceedingsDetailsTranslated;

    @CCD(
        label = "PCQ ID"
    )
    private String pcqId;

    @CCD(
        label = "The applicant wants to continue with their application."
    )
    private YesOrNo continueApplication;

    @JsonProperty("Offline") // required because isOffline() confuses Jackson
    private YesOrNo offline;

    @CCD(
        label = "CO Pronounced cover letter regenerated",
        access = {DefaultAccess.class}
    )
    private YesOrNo coPronouncedCoverLetterRegenerated;

}
