package uk.gov.hmcts.reform.fpl.model;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.FieldType;
import ccd.sdk.types.Label;
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
    @Label(id = "gateKeeperLabel", value = "Let the gatekeeper know there's a new case")
    private final String gatekeeperEmail;
    @CaseField(label = "Local Authority :", type = FieldType.FixedList)
    private final String caseLocalAuthority;
    private final Risks risks;
    @NotNull(message = "You need to add details to orders and directions needed")
    @Valid
    private final Orders orders;
    @NotNull(message = "You need to add details to grounds for the application")
    @Valid
    private final Grounds grounds;
    @NotNull(message = "You need to add details to grounds for the application", groups = EPOGroup.class)
    @Valid
    private final GroundsForEPO groundsForEPO;
    @NotNull(message = "You need to add details to applicant")
    @Valid
    private final List<@NotNull(message = "You need to add details to applicant")
            Element<Applicant>> applicants;
    @NotNull(message = "You need to add details to respondents")
    private final List<@NotNull(message = "You need to add details to respondents") Element<Respondent>> respondents1;

    @Valid
    private final Respondent getFirstRespondent() {
        if (isEmpty(respondents1)) {
            return Respondent.builder().build();
        }

        return respondents1.get(0).getValue();
    }

    private final Proceeding proceeding;

    @NotNull(message = "You need to add details to solicitor")
    @Valid
    private final Solicitor solicitor;
    private final FactorsParenting factorsParenting;
    @Label(id = "allocationProposal_label", value = "This should be completed by a solicitor with good knowledge of the case. Use the [President's Guidance](https://www.judiciary.uk/wp-content/uploads/2013/03/President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) and [schedule](https://www.judiciary.uk/wp-content/uploads/2013/03/Schedule-to-the-President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) on allocation and gatekeeping to make your recommendation.")
    private final Allocation allocationProposal;
    private final Allocation allocationDecision;
    @Label(id="allPartiesPrecedentLabelCMO", value="Add completed directions from the precedent library or your own template.")
    private final List<Element<Direction>> allParties;
    private final List<Element<Direction>> allPartiesCustom;
    private final List<Element<Direction>> localAuthorityDirections;
    private final List<Element<Direction>> localAuthorityDirectionsCustom;
    private final List<Element<Direction>> courtDirections;
    private final List<Element<Direction>> courtDirectionsCustom;
    private final List<Element<Direction>> cafcassDirections;
    private final List<Element<Direction>> cafcassDirectionsCustom;
    private final List<Element<Direction>> otherPartiesDirections;
    private final List<Element<Direction>> otherPartiesDirectionsCustom;
    private final List<Element<Direction>> respondentDirections;
    private final List<Element<Direction>> respondentDirectionsCustom;
    private final Order standardDirectionOrder;
    @NotNull(message = "You need to add details to hearing needed")
    @Valid
    private final Hearing hearing;
    private final HearingPreferences hearingPreferences;
    private final InternationalElement internationalElement;
    @JsonProperty("documents_socialWorkOther")
    private final List<Element<DocumentSocialWorkOther>> otherSocialWorkDocuments;
    @JsonProperty("documents_socialWorkCarePlan_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document socialWorkCarePlanDocument;
    @JsonProperty("documents_socialWorkStatement_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document socialWorkStatementDocument;
    @JsonProperty("documents_socialWorkAssessment_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document socialWorkAssessmentDocument;
    @JsonProperty("documents_socialWorkChronology_document")
    @Label(id = "uploadDocuments_paragraph_1", value = "You must upload these documents if possible. Give the reason and date you expect to provide it if you don’t have a document yet.")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document socialWorkChronologyDocument;
    @JsonProperty("documents_checklist_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document checklistDocument;
    @JsonProperty("documents_threshold_document")
    @NotNull(message = "Tell us the status of all documents including those that you haven't uploaded")
    @Valid
    public final Document thresholdDocument;
    @JsonProperty("documents_socialWorkEvidenceTemplate_document")
    @Valid
    private final Document socialWorkEvidenceTemplateDocument;
    @NotNull(message = "You need to add details to children")
    @Valid
    private final List<@NotNull(message = "You need to add details to children") Element<Child>> children1;
    @NotBlank(message = "Enter Familyman case number", groups = {NoticeOfProceedingsGroup.class,
        C21CaseOrderGroup.class, NotifyGatekeeperGroup.class})
    private final String familyManCaseNumber;
    private final NoticeOfProceedings noticeOfProceedings;

    public List<Element<Applicant>> getAllApplicants() {
        return applicants != null ? applicants : new ArrayList<>();
    }

    public List<Element<Child>> getAllChildren() {
        return children1 != null ? children1 : new ArrayList<>();
    }

    @NotNull(message = "Enter hearing details", groups = NoticeOfProceedingsGroup.class)
    private final List<Element<HearingBooking>> hearingDetails;

    private LocalDate dateSubmitted;
    @Label(id = "hearingDate", value = "The next hearing is on ${hearingDetails.startDate}.")
    private final List<Element<DocumentBundle>> noticeOfProceedingsBundle;
    @Label(id="c9Declaration", value="If you send documents to a party's solicitor or a children's guardian, give their details")
    private final List<Element<Recipients>> statementOfService;
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;
    private final C2DocumentBundle temporaryC2Document;
    private final List<Element<C2DocumentBundle>> c2DocumentBundle;
    private final C21Order c21Order;
    private final List<Element<C21Order>> c21Orders;

    public List<Element<C21Order>> getC21Orders() {
        return defaultIfNull(c21Orders, new ArrayList<>());
    }

    private final CaseManagementOrder caseManagementOrder;
    private final Others others;
}
