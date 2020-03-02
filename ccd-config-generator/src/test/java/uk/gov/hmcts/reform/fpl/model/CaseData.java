package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.model.common.*;
import uk.gov.hmcts.reform.fpl.validation.groups.*;
import uk.gov.hmcts.reform.fpl.validation.interfaces.HasDocumentsIncludedInSwet;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static org.apache.commons.lang3.ObjectUtils.isEmpty;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@HasDocumentsIncludedInSwet(groups = UploadDocumentsGroup.class)
public class CaseData {
    @NotBlank(message = "Enter a case name")
    @CCD(label = "Case name",
    hint = "Include the Local Authority name and respondent’s last name. For example, Endley Council v Smith/Tate/Jones")
    private final String caseName;
    @CCD(type = FieldType.Email, label = "Gatekeeper's email address", hint = "For example, joe.bloggs@la.gov.uk")
    private final String gatekeeperEmail;
    @CCD(label = "Local Authority :", type = FieldType.FixedList, typeParameter = "AuthorityFixedList")
    private final String caseLocalAuthority;
    @CCD(label = "Risks and harm to children")
    private final Risks risks;
    @NotNull(message = "You need to add details to orders and directions needed")
    @Valid
    @CCD(label = "Orders and directions needed")
    private final Orders orders;

    @NotNull(message = "You need to add details to grounds for the application")
    @Valid
    @CCD(label = "How does this case meet the threshold criteria?")
    private final Grounds grounds;

    @NotNull(message = "You need to add details to grounds for the application", groups = EPOGroup.class)
    @Valid
    @CCD(label = "How are there grounds for an emergency protection order?")
    private final GroundsForEPO groundsForEPO;

    @NotNull(message = "You need to add details to applicant")
    @Valid
    @CCD(label = "Applicants")
    private final List<@NotNull(message = "You need to add details to applicant")
            Element<Applicant>> applicants;
    @NotNull(message = "You need to add details to respondents")
    @CCD(label = "Respondents")
    private final List<@NotNull(message = "You need to add details to respondents") Element<Respondent>> respondents1;

    @Valid
    private final Respondent getFirstRespondent() {
        if (isEmpty(respondents1)) {
            return Respondent.builder().build();
        }

        return respondents1.get(0).getValue();
    }

    @CCD(label = "Other proceedings")
    private final Proceeding proceeding;

    @NotNull(message = "You need to add details to solicitor")
    @Valid
    @CCD(label = "Solicitor")
    private final Solicitor solicitor;

    @CCD(label = "Factors affecting parenting")
    private final FactorsParenting factorsParenting;
    @CCD(label = "Allocation proposal")
    private final AllocationProposal allocationProposal;
    @CCD(label = "Allocation decision", showSummaryContent = true)
    private final AllocationDecision allocationDecision;
    @CCD(label = "For all parties")
    private final List<Element<Direction>> allParties;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> allPartiesCustom;
    @CCD(label = "Local Authority Directions")
    private final List<Element<Direction>> localAuthorityDirections;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> localAuthorityDirectionsCustom;
    @CCD(label = "Court directions")
    private final List<Element<Direction>> courtDirections;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> courtDirectionsCustom;
    @CCD(label = "Cafcass Directions")
    private final List<Element<Direction>> cafcassDirections;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> cafcassDirectionsCustom;
    @CCD(label = "Other parties directions")
    private final List<Element<Direction>> otherPartiesDirections;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> otherPartiesDirectionsCustom;
    @CCD(label = "Parents and respondents directions")
    private final List<Element<Direction>> respondentDirections;
    @CCD(label = "Add directions")
    private final List<Element<Direction>> respondentDirectionsCustom;

    @CCD(label = "Standard directions order")
    private final Order standardDirectionOrder;

    @NotNull(message = "You need to add details to hearing needed")
    @Valid
    @CCD(label = "Hearing needed")
    private final Hearing hearing;
    @CCD(label = "Attending the hearing")
    private final HearingPreferences hearingPreferences;

    @CCD(label = "International element")
    private final InternationalElement internationalElement;
    @CCD(label = "Additional documents")
    @JsonProperty("documents_socialWorkOther")
    private final List<Element<DocumentSocialWorkOther>> otherSocialWorkDocuments;
    @JsonProperty("documents_socialWorkCarePlan_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "4. Care plan")
    public final Document socialWorkCarePlanDocument;
    @JsonProperty("documents_socialWorkStatement_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "2. Social work statement and genogram")
    public final Document socialWorkStatementDocument;
    @JsonProperty("documents_socialWorkAssessment_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "3. Social work assessment")
    public final Document socialWorkAssessmentDocument;
    @JsonProperty("documents_socialWorkChronology_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "1. Social work chronology")
    public final Document socialWorkChronologyDocument;
    @JsonProperty("documents_checklist_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "7. Checklist document")
    public final Document checklistDocument;
    @JsonProperty("documents_threshold_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CCD(label = "6. Threshold document")
    public final Document thresholdDocument;
    @JsonProperty("documents_socialWorkEvidenceTemplate_document")
    @Valid
    @CCD(label = "5. Social work evidence template (SWET)")
    private final SWETDocument socialWorkEvidenceTemplateDocument;
    @NotNull(message = "You need to add details to children")
    @Valid
    @CCD(label = "Child")
    private final List<@NotNull(message = "You need to add details to children") Element<Child>> children1;
    @NotBlank(message = "Enter Familyman case number", groups = {NoticeOfProceedingsGroup.class,
        C21CaseOrderGroup.class, NotifyGatekeeperGroup.class})
    @CCD(label = "FamilyMan case number")
    private final String familyManCaseNumber;
    @CCD(label = " ")
    private final NoticeOfProceedings noticeOfProceedings;

    public List<Element<Applicant>> getAllApplicants() {
        return applicants != null ? applicants : new ArrayList<>();
    }

    public List<Element<Child>> getAllChildren() {
        return children1 != null ? children1 : new ArrayList<>();
    }

    @NotNull(message = "Enter hearing details", groups = NoticeOfProceedingsGroup.class)
    @CCD(label = "Hearing")
    private final List<Element<HearingBooking>> hearingDetails;

    @CCD(label = "Date submitted")
    private LocalDate dateSubmitted;
    @CCD(label = "Notice of proceedings")
    private final List<Element<DocumentBundle>> noticeOfProceedingsBundle;
    @CCD(label = "Recipients")
    private final List<Element<Recipients>> statementOfService;
    @CCD(label = "Judge and legal advisor")
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;

    @CCD(label = "Upload C2")
    private final C2DocumentBundle temporaryC2Document;
    @CCD(label = "C2")
    private final List<Element<C2DocumentBundle>> c2DocumentBundle;
    @CCD(label = "Create an order")
    private final C21Order c21Order;
    @CCD(label = "C21 order")
    private final List<Element<C21Order>> c21Orders;

    public List<Element<C21Order>> getC21Orders() {
        return defaultIfNull(c21Orders, new ArrayList<>());
    }

    @CCD(label = "Case management order")
    private final CaseManagementOrder caseManagementOrder;
    @CCD(label = "Others to be given notice")
    private final Others others;
}
