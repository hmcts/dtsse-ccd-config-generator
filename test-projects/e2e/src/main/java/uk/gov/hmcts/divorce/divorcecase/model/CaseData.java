package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.External;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.ComponentLauncher;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.SearchCriteria;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerCaseLinkAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerAndSuperUserAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerWithCAAAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.DefaultAccess;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class CaseData {

    @CCD(
        label = "Application type",
        access = {DefaultAccess.class},
        typeOverride = FieldType.FixedRadioList,
        typeParameterOverride = "ApplicationType"
    )
    private ApplicationType applicationType;

    @CCD(access = {DefaultAccess.class})
    private String setInAboutToStart;

    @CCD(access = {DefaultAccess.class})
    private String setInMidEvent;

    @CCD(access = {DefaultAccess.class})
    private String setInAboutToSubmit;

    @CCD(
        label = "A Field",
        access = {DefaultAccess.class}
    )
    private String aField;

    @JsonUnwrapped(prefix = "applicant1")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant1 = new Applicant();

    @JsonUnwrapped(prefix = "applicant2")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant2 = new Applicant();

    @CCD(
        label = "Notes",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseNote",
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private List<ListValue<CaseNote>> notes;

    @CCD(
        label = "Linked cases",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseLink",
        access = {CaseworkerCaseLinkAccess.class}
    )
    @Builder.Default
    private List<ListValue<CaseLink>> caseLinks = new ArrayList<>();

    @CCD(
        label = "Component launcher for linked cases",
        access = {CaseworkerCaseLinkAccess.class}
    )
    @JsonProperty("LinkedCasesComponentLauncher")
    private ComponentLauncher linkedCasesComponentLauncher;

    @CCD(
        label = "Add a case note",
        hint = "Enter note",
        typeOverride = FieldType.TextArea,
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private String note;

    @CCD(
        label = "Global Search reference",
        access = {DefaultAccess.class}
    )
    private String searchCriteriaCaseReference;

    @CCD(
        label = "Search Criteria",
        access = {DefaultAccess.class}
    )
    @SuppressWarnings("MemberName") // Field name is case-sensitive in CCD
    @JsonProperty("SearchCriteria")
    private SearchCriteria SearchCriteria;

    @CCD(
        label = "Due Date",
        access = {DefaultAccess.class, CaseworkerWithCAAAccess.class}
    )
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @CCD(access = {CaseworkerAccess.class})
    @External
    private String hyphenatedCaseRef;

    @CCD(
        label = "Test document",
        typeOverride = FieldType.Document
    )
    private Document testDocument;

    @JsonIgnore
    public static String formatCaseRef(long caseId) {
        String temp = String.format("%016d", caseId);
        return String.format("%4s-%4s-%4s-%4s",
            temp.substring(0, 4),
            temp.substring(4, 8),
            temp.substring(8, 12),
            temp.substring(12, 16)
        );
    }

    /**
     * NEW CIVIL CASE DATA FIELDS
     */

    @CCD(
        label = "Case reference",
        hint = "The unique reference for this case"
    )
    private String caseReference;

    @CCD(
        label = "Case name",
        hint = "The name of the case"
    )
    private String caseName;

    @CCD(
        label = "Case description",
        hint = "A description of the case"
    )
    private String caseDescription;

    @CCD(
        label = "Date created",
        hint = "The date the case was created"
    )
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime dateCreated;

    @CCD(
        label = "Due date",
        hint = "The due date for this case"
    )
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @CCD(
        label = "Is urgent",
        hint = "Whether this case is urgent"
    )
    private YesOrNo isUrgent;

    // CREATE_CLAIM journey fields
    @CCD(
        label = "Is the claimant a child",
        hint = "Is the claimant a child i.e. under the age of 18?"
    )
    private YesOrNo isClaimantChild;

    @CCD(
        label = "Claimant's legal representative's reference",
        hint = "Your file reference (Optional)"
    )
    private String claimantLegalRepresentativeReference;

    @CCD(
        label = "Defendant's legal representative's reference",
        hint = "Your file reference (Optional)"
    )
    private String defendantLegalRepresentativeReference;

    @CCD(
        label = "What type of claim is this?",
        hint = "Select the type of claim",
        typeOverride = FieldType.FixedRadioList
    )
    private ClaimType claimType;

    @CCD(
        label = "Does the defendant have a legal representative?",
        hint = "Does the defendant have a legal representative?"
    )
    private YesOrNo doesDefendantHaveLegalRepresentative;

    @CCD(
        label = "Would you like to use the same email address for notifications?",
        hint = "Would you like to use the same email address for notifications related to this claim?"
    )
    private YesOrNo useSameEmailForNotifications;

    @CCD(
        label = "Do you want to add another claimant now?",
        hint = "You can add another Claimant after you have issued the claim but you will need to submit a separate application."
    )
    private YesOrNo addAnotherClaimant;

    @CCD(
        label = "Do you wish to enter a different address?",
        hint = "Postal correspondence for the Claimant's legal representative will be sent to the address registered with MyHMCTS. You can, if you wish, change the address to which postal correspondence is sent (eg if you work out of a different office from the address registered with MyHMCTS)."
    )
    private YesOrNo enterDifferentLegalRepAddress;

    @CCD(
        label = "Is there another defendant?",
        hint = "Is there another defendant?"
    )
    private YesOrNo isThereAnotherDefendant;

    @CCD(
        label = "Do you want to change the address?",
        hint = "Postal correspondence to the Defendant's legal representative will be sent to the address that is currently registered with MyHMCTS. You can, if you wish, change the address to which postal correspondence is sent."
    )
    private YesOrNo enterDifferentDefendantLegalRepAddress;

    @CCD(
        label = "Please select your preferred court hearing location",
        hint = "Court location code",
        typeOverride = FieldType.FixedList
    )
    private String courtLocationCode;

    @CCD(
        label = "Briefly explain your reasons",
        hint = "Briefly explain your reasons (Optional)",
        typeOverride = FieldType.TextArea
    )
    private String courtLocationReason;

    @CCD(
        label = "Do you want the hearing to be held remotely?",
        hint = "This will be over telephone or video"
    )
    private YesOrNo hearingHeldRemotely;

    @CCD(
        label = "Claimant details"
    )
    private Claimant claimant;

    @CCD(
        label = "Defendant details"
    )
    private Defendant defendant;

    @CCD(
        label = "Claimant's legal representative"
    )
    private LegalRepresentative claimantLegalRepresentative;

    @CCD(
        label = "Defendant's legal representative"
    )
    private LegalRepresentative defendantLegalRepresentative;

}
