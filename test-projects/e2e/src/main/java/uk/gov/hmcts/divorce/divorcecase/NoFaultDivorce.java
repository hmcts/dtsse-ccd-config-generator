package uk.gov.hmcts.divorce.divorcecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.Claimant;
import uk.gov.hmcts.divorce.divorcecase.model.Defendant;
import uk.gov.hmcts.divorce.divorcecase.model.LegalRepresentative;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.ccd.sdk.api.Permission.R;

@Component
@Slf4j
public class NoFaultDivorce implements CCDConfig<CaseData, State, UserRole> {

    private static final String CASE_TYPE = "E2E_CIVIL";
    public static final String CASE_TYPE_DESCRIPTION = "New Civil Case";
    public static final String JURISDICTION = "CIVIL";
    public static final String CREATE_CLAIM_EVENT = "CREATE_CLAIM";
    public static final String CREATE_EVENT = "CREATE_CASE";
    public static final String SUBMIT_EVENT = "SUBMIT_CASE";
    public static final String UPDATE_EVENT = "UPDATE_CASE";

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
                .mandatoryWithLabel(CaseData::getIsClaimantChild, "Is the claimant a child i.e. under the age of 18?")
                .done()
            .fields()
                // Page 2: File references
                .page("fileReferences")
                .label("fileReferencesLabel", "## Your File Reference (Optional)")
                .optionalWithLabel(
                    CaseData::getClaimantLegalRepresentativeReference,
                    "Claimant's legal representative's reference (Optional)"
                )
                .optionalWithLabel(
                    CaseData::getDefendantLegalRepresentativeReference,
                    "Defendant's legal representative's reference (Optional)"
                )
                .done()
            .fields()
                // Page 3: Claim type
                .page("claimType")
                .label("claimTypeLabel", "## Create claim - Unspecified")
                .mandatoryWithLabel(CaseData::getClaimType, "What type of claim is this?")
                .done()
            .fields()
                // Page 4: Claimant details
                .page("claimantDetails")
                .label("claimantDetailsLabel", "## Claimant's details")
                .complex(CaseData::getClaimant)
                    .mandatoryWithLabel(Claimant::getType, "Claimant type")
                    .optionalWithLabel(Claimant::getTitle, "Title (Optional)")
                    .mandatoryWithLabel(Claimant::getFirstName, "First name")
                    .mandatoryWithLabel(Claimant::getLastName, "Last name")
                    .mandatoryWithLabel(Claimant::getDateOfBirth, "Date of birth")
                    .optionalWithLabel(Claimant::getEmail, "Email (Optional)")
                    .optionalWithLabel(Claimant::getPhone, "Phone (Optional)")
                    .done()
                .label("addressLabel", "## Address")
                .complex(CaseData::getClaimant)
                    .complex(Claimant::getAddress)
                        .mandatoryWithLabel(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 5: Search for claimant's legal representative
                .page("claimantLegalRepresentative")
                .label("claimantLegalRepresentativeLabel", "## Search for the claimant's legal representative")
                .complex(CaseData::getClaimantLegalRepresentative)
                    .optionalWithLabel(LegalRepresentative::getReference, "Reference (Optional)")
                    .optionalWithLabel(LegalRepresentative::getOrganisationName, "Search for an organisation")
                    .done()
                .done()
            .fields()
                // Page 6: Legal representative's service address
                .page("legalRepresentativeServiceAddress")
                .label("legalRepresentativeServiceAddressLabel", "## Legal representative's service address")
                .mandatoryWithLabel(
                    CaseData::getEnterDifferentLegalRepAddress,
                    "Do you wish to enter a different address?"
                )
                .complex(CaseData::getClaimantLegalRepresentative)
                    .complex(LegalRepresentative::getServiceAddress)
                        .mandatoryWithLabel(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 7: Notifications
                .page("notifications")
                .label("notificationsLabel", "## Notifications")
                .mandatoryWithLabel(
                    CaseData::getUseSameEmailForNotifications,
                    "Would you like to use the same email address for notifications related to this claim?"
                )
                .done()
            .fields()
                // Page 8: Add another claimant?
                .page("addAnotherClaimant")
                .label("addAnotherClaimantLabel", "## Create claim - Unspecified")
                .mandatoryWithLabel(CaseData::getAddAnotherClaimant, "Do you want to add another claimant now?")
                .done()
            .fields()
                // Page 9: Does the defendant have a legal representative?
                .page("defendantHasLegalRepresentative")
                .label("defendantHasLegalRepresentativeLabel", "## Create claim - Unspecified")
                .mandatoryWithLabel(
                    CaseData::getDoesDefendantHaveLegalRepresentative,
                    "Does the defendant have a legal representative?"
                )
                .done()
            .fields()
                // Page 10: Defendant details
                .page("defendantDetails")
                .label("defendantDetailsLabel", "## Defendant details")
                .complex(CaseData::getDefendant)
                    .mandatoryWithLabel(Defendant::getType, "Defendant type")
                    .optionalWithLabel(Defendant::getTitle, "Title (Optional)")
                    .mandatoryWithLabel(Defendant::getFirstName, "First name")
                    .mandatoryWithLabel(Defendant::getLastName, "Last name")
                    .optionalWithLabel(Defendant::getEmail, "Email (Optional)")
                    .optionalWithLabel(Defendant::getPhone, "Phone (Optional)")
                    .done()
                .label("defendantAddressLabel", "## Address")
                .complex(CaseData::getDefendant)
                    .complex(Defendant::getAddress)
                        .mandatoryWithLabel(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 11: Search for defendant's legal representative (conditional)
                .page("defendantLegalRepresentative")
                .label("defendantLegalRepresentativeLabel", "## Search for the defendant's legal representative")
                .complex(CaseData::getDefendantLegalRepresentative)
                    .optionalWithLabel(LegalRepresentative::getReference, "Reference (Optional)")
                    .optionalWithLabel(LegalRepresentative::getOrganisationName, "Search for an organisation")
                    .done()
                .done()
            .fields()
                // Page 12: Defendant legal representative's address
                .page("defendantLegalRepresentativeAddress")
                .label("defendantLegalRepresentativeAddressLabel", "## Defendant legal representative's address")
                .mandatoryWithLabel(
                    CaseData::getEnterDifferentDefendantLegalRepAddress,
                    "Do you want to change the address?"
                )
                .complex(CaseData::getDefendantLegalRepresentative)
                    .complex(LegalRepresentative::getServiceAddress)
                        .mandatoryWithLabel(uk.gov.hmcts.ccd.sdk.type.AddressUK::getPostCode, "Enter a UK postcode")
                        .done()
                    .done()
                .done()
            .fields()
                // Page 13: Defendant legal representative's email
                .page("defendantLegalRepresentativeEmail")
                .label("defendantLegalRepresentativeEmailLabel", "## Create claim - Unspecified")
                .complex(CaseData::getDefendantLegalRepresentative)
                    .mandatoryWithLabel(
                        LegalRepresentative::getEmailAddress,
                        "Enter defendant legal representative's email address to be used for notifications"
                    )
                    .done()
                .done()
            .fields()
                // Page 14: Is there another defendant?
                .page("isThereAnotherDefendant")
                .label("isThereAnotherDefendantLabel", "## Create claim - Unspecified")
                .mandatoryWithLabel(CaseData::getIsThereAnotherDefendant, "Is there another defendant?")
                .done()
            .fields()
                // Page 15: Court location and hearing preferences
                .page("courtLocation")
                .label("courtLocationLabel", "## Court location code")
                .mandatoryWithLabel(
                    CaseData::getCourtLocationCode,
                    "Please select your preferred court hearing location"
                )
                .optionalWithLabel(CaseData::getCourtLocationReason, "Briefly explain your reasons (Optional)")
                .mandatoryWithLabel(CaseData::getHearingHeldRemotely, "Do you want the hearing to be held remotely?")
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
            .forStateTransition(EnumSet.noneOf(State.class), State.CREATED)
            .name("Create case")
            .description("Create a new civil case")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .aboutToStartCallback(this::aboutToStartCreate)
            .aboutToSubmitCallback(this::aboutToSubmitCreate)
            .fields()
                .page("caseDetails")
                .label("caseDetailsLabel", "## Case Details")
                .mandatoryWithLabel(CaseData::getCaseName, "Case name")
                .optionalWithLabel(CaseData::getCaseDescription, "Case description")
                .optionalWithLabel(CaseData::getDueDate, "Due date")
                .optionalWithLabel(CaseData::getIsUrgent, "Is urgent")
                .done()
            .fields()
                .page("claimantDetails")
                .label("claimantDetailsLabel", "## Claimant Details")
                .complex(CaseData::getClaimant)
                    .mandatoryWithLabel(Claimant::getFirstName, "Claimant first name")
                    .mandatoryWithLabel(Claimant::getLastName, "Claimant last name")
                    .optionalWithLabel(Claimant::getEmail, "Claimant email")
                    .optionalWithLabel(Claimant::getAddress, "Claimant address")
                    .done()
                .done()
            .fields()
                .page("defendantDetails")
                .label("defendantDetailsLabel", "## Defendant Details")
                .complex(CaseData::getDefendant)
                    .mandatoryWithLabel(Defendant::getFirstName, "Defendant first name")
                    .mandatoryWithLabel(Defendant::getLastName, "Defendant last name")
                    .optionalWithLabel(Defendant::getEmail, "Defendant email")
                    .optionalWithLabel(Defendant::getAddress, "Defendant address")
                    .done()
                .done();

        // Submit case event
        builder.event(SUBMIT_EVENT)
            .forStateTransition(State.CREATED, State.Submitted)
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
            .forStateTransition(
                EnumSet.of(State.CREATED, State.Submitted, State.AwaitingPayment),
                EnumSet.of(State.CREATED, State.Submitted, State.AwaitingPayment)
            )
            .name("Update case")
            .description("Update case details")
            .grant(CRU, UserRole.CASE_WORKER, UserRole.SOLICITOR)
            .fields()
                .page("updateCase")
                .optionalWithLabel(CaseData::getCaseDescription, "Case description")
                .optionalWithLabel(CaseData::getDueDate, "Due date")
                .optionalWithLabel(CaseData::getIsUrgent, "Is urgent")
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

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToStartCreateClaim(
        CaseDetails<CaseData, State> details
    ) {
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmitCreateClaim(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> before
    ) {
        var data = details.getData();
        ensureCaseMetadata(data, details);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(data)
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToStartCreate(
        CaseDetails<CaseData, State> details
    ) {
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmitCreate(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> before
    ) {
        var data = details.getData();
        ensureCaseMetadata(data, details);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(data)
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmitCase(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> before
    ) {
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    private void ensureCaseMetadata(CaseData data, CaseDetails<CaseData, State> details) {
        if (data.getDateCreated() == null) {
            data.setDateCreated(LocalDateTime.now());
        }
        if (data.getCaseReference() == null && details.getId() != null) {
            data.setCaseReference(details.getId().toString());
        }
    }
}
