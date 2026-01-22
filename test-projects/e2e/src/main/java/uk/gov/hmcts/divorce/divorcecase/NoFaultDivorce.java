package uk.gov.hmcts.divorce.divorcecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.APPLICANT_1_SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.APPLICANT_2_SOLICITOR;

@Component
@Slf4j
public class NoFaultDivorce implements CCDConfig<CaseData, State, UserRole> {

    private static final String CASE_TYPE = "E2E";
    public static final String CASE_TYPE_DESCRIPTION = "New Civil Case";
    public static final String JURISDICTION = "CIVIL";
    public static final String CREATE_CLAIM_EVENT = "CREATE_CLAIM";

    public static String getCaseType() {
        return CASE_TYPE;
    }

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.setCallbackHost(System.getenv().getOrDefault("API_URL", "http://localhost:4013"));
        builder.caseType(CASE_TYPE, CASE_TYPE_DESCRIPTION, "Civil case type for decentralised prototype");
        builder.jurisdiction(JURISDICTION, "Civil", "Civil jurisdiction");

        // CREATE_CLAIM journey - Main event for creating a new claim
        builder.event(CREATE_CLAIM_EVENT)
            .forStateTransition(EnumSet.noneOf(State.class), State.AwaitingPayment)
            .name("Create claim")
            .description("Create a new civil claim")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR, UserRole.CITIZEN)
            .aboutToStartCallback(this::aboutToStartCreateClaim)
            .aboutToSubmitCallback(this::aboutToSubmitCreateClaim)
            .fields()
                // Page 1: Is the claimant a child?
                .page("isClaimantChild")
                .label("isClaimantChildLabel", "## Create claim - Unspecified")
                .mandatory(CaseData::getIsClaimantChild, "Is the claimant a child i.e. under the age of 18?")
                .done()
            .fields()
                // Page 2: File references
                .page("fileReferences")
                .label("fileReferencesLabel", "## Your File Reference (Optional)")
                .optional(CaseData::getClaimantLegalRepresentativeReference, "Claimant's legal representative's reference (Optional)")
                .optional(CaseData::getDefendantLegalRepresentativeReference, "Defendant's legal representative's reference (Optional)")
                .done()
            .fields()
                // Page 3: Claim type
                .page("claimType")
                .label("claimTypeLabel", "## Create claim - Unspecified")
                .mandatory(CaseData::getClaimType, "What type of claim is this?")
                .done()
            .fields()
                // Page 4: Claimant details
                .page("claimantDetails")
                .label("claimantDetailsLabel", "## Claimant's details")
                .complex(CaseData::getClaimant)
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Claimant::getType, "Claimant type")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Claimant::getTitle, "Title (Optional)")
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Claimant::getFirstName, "First name")
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Claimant::getLastName, "Last name")
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Claimant::getDateOfBirth, "Date of birth")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Claimant::getEmail, "Email (Optional)")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Claimant::getPhone, "Phone (Optional)")
                    .done()
                .label("addressLabel", "## Address")
                .complex(CaseData::getClaimant)
                    .complex(uk.gov.hmcts.civil.civilcase.model.Claimant::getAddress)
                        .mandatory(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 5: Search for claimant's legal representative
                .page("claimantLegalRepresentative")
                .label("claimantLegalRepresentativeLabel", "## Search for the claimant's legal representative")
                .complex(CaseData::getClaimantLegalRepresentative)
                    .optional(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getReference, "Reference (Optional)")
                    .optional(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getOrganisationName, "Search for an organisation")
                    .done()
                .done()
            .fields()
                // Page 6: Legal representative's service address
                .page("legalRepresentativeServiceAddress")
                .label("legalRepresentativeServiceAddressLabel", "## Legal representative's service address")
                .mandatory(CaseData::getEnterDifferentLegalRepAddress, "Do you wish to enter a different address?")
                .complex(CaseData::getClaimantLegalRepresentative)
                    .complex(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getServiceAddress)
                        .mandatory(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 7: Notifications
                .page("notifications")
                .label("notificationsLabel", "## Notifications")
                .mandatory(CaseData::getUseSameEmailForNotifications, "Would you like to use the same email address for notifications related to this claim?")
                .done()
            .fields()
                // Page 8: Add another claimant?
                .page("addAnotherClaimant")
                .label("addAnotherClaimantLabel", "## Create claim - Unspecified")
                .mandatory(CaseData::getAddAnotherClaimant, "Do you want to add another claimant now?")
                .done()
            .fields()
                // Page 9: Does the defendant have a legal representative?
                .page("defendantHasLegalRepresentative")
                .label("defendantHasLegalRepresentativeLabel", "## Create claim - Unspecified")
                .mandatory(CaseData::getDoesDefendantHaveLegalRepresentative, "Does the defendant have a legal representative?")
                .done()
            .fields()
                // Page 10: Defendant details
                .page("defendantDetails")
                .label("defendantDetailsLabel", "## Defendant details")
                .complex(CaseData::getDefendant)
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Defendant::getType, "Defendant type")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Defendant::getTitle, "Title (Optional)")
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Defendant::getFirstName, "First name")
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Defendant::getLastName, "Last name")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Defendant::getEmail, "Email (Optional)")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Defendant::getPhone, "Phone (Optional)")
                    .done()
                .label("defendantAddressLabel", "## Address")
                .complex(CaseData::getDefendant)
                    .complex(uk.gov.hmcts.civil.civilcase.model.Defendant::getAddress)
                        .mandatory(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 11: Search for defendant's legal representative (conditional)
                .page("defendantLegalRepresentative")
                .label("defendantLegalRepresentativeLabel", "## Search for the defendant's legal representative")
                .complex(CaseData::getDefendantLegalRepresentative)
                    .optional(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getReference, "Reference (Optional)")
                    .optional(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getOrganisationName, "Search for an organisation")
                    .done()
                .done()
            .fields()
                // Page 12: Defendant legal representative's address
                .page("defendantLegalRepresentativeAddress")
                .label("defendantLegalRepresentativeAddressLabel", "## Defendant legal representative's address")
                .mandatory(CaseData::getEnterDifferentDefendantLegalRepAddress, "Do you want to change the address?")
                .complex(CaseData::getDefendantLegalRepresentative)
                    .complex(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getServiceAddress)
                        .mandatory(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 13: Defendant legal representative's email
                .page("defendantLegalRepresentativeEmail")
                .label("defendantLegalRepresentativeEmailLabel", "## Create claim - Unspecified")
                .complex(CaseData::getDefendantLegalRepresentative)
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.LegalRepresentative::getEmailAddress, "Enter defendant legal representative's email address to be used for notifications")
                    .done()
                .done()
            .fields()
                // Page 14: Is there another defendant?
                .page("isThereAnotherDefendant")
                .label("isThereAnotherDefendantLabel", "## Create claim - Unspecified")
                .mandatory(CaseData::getIsThereAnotherDefendant, "Is there another defendant?")
                .done()
            .fields()
                // Page 15: Court location and hearing preferences
                .page("courtLocation")
                .label("courtLocationLabel", "## Court location code")
                .mandatory(CaseData::getCourtLocationCode, "Please select your preferred court hearing location")
                .optional(CaseData::getCourtLocationReason, "Briefly explain your reasons (Optional)")
                .mandatory(CaseData::getHearingHeldRemotely, "Do you want the hearing to be held remotely?")
                .done()
            .fields()
                // Page 16: Requirements/Information (readonly)
                .page("requirements")
                .label("requirementsLabel", "## Issue civil court proceedings")
                .readonly(CaseData::getClaimType)
                .readonly(CaseData::getClaimant)
                .readonly(CaseData::getDefendant)
                .done();

        // Create case event
        builder.event(CREATE_EVENT)
            .forStateTransition(EnumSet.noneOf(State.class), State.CASE_CREATED)
            .name("Create case")
            .description("Create a new civil case")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .aboutToStartCallback(this::aboutToStartCreate)
            .aboutToSubmitCallback(this::aboutToSubmitCreate)
            .fields()
                .page("caseDetails")
                .label("caseDetailsLabel", "## Case Details")
                .mandatory(CaseData::getCaseName, "Case name")
                .optional(CaseData::getCaseDescription, "Case description")
                .optional(CaseData::getDueDate, "Due date")
                .optional(CaseData::getIsUrgent, "Is urgent")
                .done()
            .fields()
                .page("claimantDetails")
                .label("claimantDetailsLabel", "## Claimant Details")
                .complex(CaseData::getClaimant)
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Claimant::getName, "Claimant name")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Claimant::getEmail, "Claimant email")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Claimant::getAddress, "Claimant address")
                    .done()
                .done()
            .fields()
                .page("defendantDetails")
                .label("defendantDetailsLabel", "## Defendant Details")
                .complex(CaseData::getDefendant)
                    .mandatory(uk.gov.hmcts.civil.civilcase.model.Defendant::getName, "Defendant name")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Defendant::getEmail, "Defendant email")
                    .optional(uk.gov.hmcts.civil.civilcase.model.Defendant::getAddress, "Defendant address")
                    .done()
                .done();

        // Submit case event
        builder.event(SUBMIT_EVENT)
            .forStateTransition(State.CASE_CREATED, State.CASE_SUBMITTED)
            .name("Submit case")
            .description("Submit the case for processing")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .grant(R, UserRole.CITIZEN)
            .aboutToSubmitCallback(this::aboutToSubmitCase)
            .fields()
                .page("submitCase")
                .label("submitCaseLabel", "## Submit Case")
                .readonly(CaseData::getCaseName)
                .readonly(CaseData::getCaseDescription)
                .done();

        // Update case event
        builder.event(UPDATE_EVENT)
            .forStateTransition(EnumSet.of(State.CASE_CREATED, State.CASE_SUBMITTED, State.CASE_IN_PROGRESS), 
                               EnumSet.of(State.CASE_CREATED, State.CASE_SUBMITTED, State.CASE_IN_PROGRESS))
            .name("Update case")
            .description("Update case details")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .fields()
                .page("updateCase")
                .optional(CaseData::getCaseDescription, "Case description")
                .optional(CaseData::getDueDate, "Due date")
                .optional(CaseData::getIsUrgent, "Is urgent")
                .done();

        // Configure tabs
        builder.tab("CaseDetailsTab", "Case Details")
            .forRoles(UserRole.CASE_WORKER, UserRole.SOLICITOR, UserRole.CITIZEN)
            .field(CaseData::getCaseReference)
            .field(CaseData::getCaseName)
            .field(CaseData::getCaseDescription)
            .field(CaseData::getDateCreated)
            .field(CaseData::getDueDate)
            .field(CaseData::getIsUrgent);

        builder.tab("ClaimantTab", "Claimant")
            .forRoles(UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .field(CaseData::getClaimant);

        builder.tab("DefendantTab", "Defendant")
            .forRoles(UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .field(CaseData::getDefendant);

        // Configure search fields
        builder.searchInputFields()
            .field(CaseData::getCaseName, "Case name")
            .caseReferenceField();

        builder.searchResultFields()
            .field(CaseData::getCaseName, "Case name")
            .field(CaseData::getCaseReference, "Case reference")
            .field(CaseData::getDateCreated, "Date created");

        // Configure work basket fields
        builder.workBasketInputFields()
            .field(CaseData::getCaseName, "Case name")
            .caseReferenceField();

        builder.workBasketResultFields()
            .field(CaseData::getCaseName, "Case name")
            .field(CaseData::getCaseReference, "Case reference")
            .field(CaseData::getDateCreated, "Date created");
    }
}
