package uk.gov.hmcts.reform.fpl.model;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.FieldType;
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
@ccd.sdk.types.CaseData
public class CaseData {
    @NotBlank(message = "Enter a case name")
    @CaseField(label = "Case name",
    hint = "Include the Local Authority name and respondent’s last name. For example, Endley Council v Smith/Tate/Jones")
    private final String caseName;
    @CaseField(type = FieldType.Email, label = "Gatekeeper's email address")
    private final String gatekeeperEmail;
    @CaseField(label = "Local Authority :", type = FieldType.FixedList)
    private final String caseLocalAuthority;
    @CaseField(label = "Risks and harm to children")
    private final Risks risks;
    @NotNull(message = "You need to add details to orders and directions needed")
    @Valid
    @CaseField(label = "Orders and directions needed")
    private final Orders orders;

    @NotNull(message = "You need to add details to grounds for the application")
    @Valid
    @CaseField(label = "How does this case meet the threshold criteria?")
    private final Grounds grounds;

    @NotNull(message = "You need to add details to grounds for the application", groups = EPOGroup.class)
    @Valid
    @CaseField(label = "How are there grounds for an emergency protection order?")
    private final GroundsForEPO groundsForEPO;

    @NotNull(message = "You need to add details to applicant")
    @Valid
    @CaseField(label = "Applicants")
    private final List<@NotNull(message = "You need to add details to applicant")
            Element<Applicant>> applicants;
    @NotNull(message = "You need to add details to respondents")
    @CaseField(label = "Respondents")
    private final List<@NotNull(message = "You need to add details to respondents") Element<Respondent>> respondents1;

    @Valid
    private final Respondent getFirstRespondent() {
        if (isEmpty(respondents1)) {
            return Respondent.builder().build();
        }

        return respondents1.get(0).getValue();
    }

    @CaseField(label = "Other proceedings")
    private final Proceeding proceeding;

    @NotNull(message = "You need to add details to solicitor")
    @Valid
    @CaseField(label = "Solicitor")
    private final Solicitor solicitor;

    @CaseField(label = "Factors affecting parenting")
    private final FactorsParenting factorsParenting;
    @CaseField(label = "Allocation proposal")
    private final Allocation allocationProposal;
    @CaseField(label = "Allocation decision")
    private final Allocation allocationDecision;
    @CaseField(label = "For all parties")
    private final List<Element<Direction>> allParties;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> allPartiesCustom;
    @CaseField(label = "Local Authority Directions")
    private final List<Element<Direction>> localAuthorityDirections;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> localAuthorityDirectionsCustom;
    @CaseField(label = "Court directions")
    private final List<Element<Direction>> courtDirections;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> courtDirectionsCustom;
    @CaseField(label = "Cafcass Directions")
    private final List<Element<Direction>> cafcassDirections;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> cafcassDirectionsCustom;
    @CaseField(label = "Other parties directions")
    private final List<Element<Direction>> otherPartiesDirections;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> otherPartiesDirectionsCustom;
    @CaseField(label = "Parents and respondents directions")
    private final List<Element<Direction>> respondentDirections;
    @CaseField(label = "Add directions")
    private final List<Element<Direction>> respondentDirectionsCustom;

    @CaseField(label = "Standard directions order")
    private final Order standardDirectionOrder;

    @NotNull(message = "You need to add details to hearing needed")
    @Valid
    @CaseField(label = "Hearing needed")
    private final Hearing hearing;
    @CaseField(label = "Attending the hearing")
    private final HearingPreferences hearingPreferences;

    @CaseField(label = "International element")
    private final InternationalElement internationalElement;
    @CaseField(label = "Additional documents")
    @JsonProperty("documents_socialWorkOther")
    private final List<Element<DocumentSocialWorkOther>> otherSocialWorkDocuments;
    @JsonProperty("documents_socialWorkCarePlan_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "4. Care plan")
    public final Document socialWorkCarePlanDocument;
    @JsonProperty("documents_socialWorkStatement_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "2. Social work statement and genogram")
    public final Document socialWorkStatementDocument;
    @JsonProperty("documents_socialWorkAssessment_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "3. Social work assessment")
    public final Document socialWorkAssessmentDocument;
    @JsonProperty("documents_socialWorkChronology_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "1. Social work chronology")
    public final Document socialWorkChronologyDocument;
    @JsonProperty("documents_checklist_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "7. Checklist document")
    public final Document checklistDocument;
    @JsonProperty("documents_threshold_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    @CaseField(label = "6. Threshold document")
    public final Document thresholdDocument;
    @JsonProperty("documents_socialWorkEvidenceTemplate_document")
    @Valid
    @CaseField(label = "5. Social work evidence template (SWET)")
    private final Document socialWorkEvidenceTemplateDocument;
    @NotNull(message = "You need to add details to children")
    @Valid
    @CaseField(label = "Child")
    private final List<@NotNull(message = "You need to add details to children") Element<Child>> children1;
    @NotBlank(message = "Enter Familyman case number", groups = {NoticeOfProceedingsGroup.class,
        C21CaseOrderGroup.class, NotifyGatekeeperGroup.class})
    @CaseField(label = "FamilyMan case number")
    private final String familyManCaseNumber;
    private final NoticeOfProceedings noticeOfProceedings;

    public List<Element<Applicant>> getAllApplicants() {
        return applicants != null ? applicants : new ArrayList<>();
    }

    public List<Element<Child>> getAllChildren() {
        return children1 != null ? children1 : new ArrayList<>();
    }

    @NotNull(message = "Enter hearing details", groups = NoticeOfProceedingsGroup.class)
    @CaseField(label = "Hearing")
    private final List<Element<HearingBooking>> hearingDetails;

    @CaseField(label = "Date submitted")
    private LocalDate dateSubmitted;
    @CaseField(label = "Notice of proceedings")
    private final List<Element<DocumentBundle>> noticeOfProceedingsBundle;
    @CaseField(label = "Recipients")
    private final List<Element<Recipients>> statementOfService;
    @CaseField(label = "Judge and legal advisor")
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;

    @CaseField(label = "Upload C2")
    private final C2DocumentBundle temporaryC2Document;
    @CaseField(label = "C2")
    private final List<Element<C2DocumentBundle>> c2DocumentBundle;
    @CaseField(label = "Create an order")
    private final C21Order c21Order;
    @CaseField(label = "C21 Orders")
    private final List<Element<C21Order>> c21Orders;

    public List<Element<C21Order>> getC21Orders() {
        return defaultIfNull(c21Orders, new ArrayList<>());
    }

    @CaseField(label = "Case management order")
    private final CaseManagementOrder caseManagementOrder;
    @CaseField(label = "Others to be given notice")
    private final Others others;
}
