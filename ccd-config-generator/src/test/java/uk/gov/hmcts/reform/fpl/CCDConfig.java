package uk.gov.hmcts.reform.fpl;


import com.google.common.base.CaseFormat;
import de.cronn.reflection.util.TypedPropertyGetter;
import uk.gov.hmcts.ccd.sdk.api.BaseCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DisplayContext;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.Allocation;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.CaseManagementOrder;
import uk.gov.hmcts.reform.fpl.model.Direction;
import uk.gov.hmcts.reform.fpl.model.DirectionResponse;
import uk.gov.hmcts.reform.fpl.model.HearingBooking;
import uk.gov.hmcts.reform.fpl.model.Judge;
import uk.gov.hmcts.reform.fpl.model.NoticeOfProceedings;
import uk.gov.hmcts.reform.fpl.model.Order;
import uk.gov.hmcts.reform.fpl.model.OrderAction;
import uk.gov.hmcts.reform.fpl.model.OrderTypeAndDocument;
import uk.gov.hmcts.reform.fpl.model.Placement;
import uk.gov.hmcts.reform.fpl.model.PlacementConfidentialDocument;
import uk.gov.hmcts.reform.fpl.model.PlacementOrderAndNotices;
import uk.gov.hmcts.reform.fpl.model.PlacementSupportingDocument;
import uk.gov.hmcts.reform.fpl.model.common.C2DocumentBundle;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;
import uk.gov.hmcts.reform.fpl.model.emergencyprotectionorder.EPOChildren;
import uk.gov.hmcts.reform.fpl.model.order.generated.GeneratedOrder;

import static uk.gov.hmcts.ccd.sdk.api.DisplayContext.Complex;
import static uk.gov.hmcts.ccd.sdk.api.DisplayContext.Mandatory;
import static uk.gov.hmcts.ccd.sdk.api.DisplayContext.Optional;
import static uk.gov.hmcts.ccd.sdk.api.DisplayContext.ReadOnly;
import static uk.gov.hmcts.reform.fpl.enums.State.Deleted;
import static uk.gov.hmcts.reform.fpl.enums.State.Gatekeeping;
import static uk.gov.hmcts.reform.fpl.enums.State.Open;
import static uk.gov.hmcts.reform.fpl.enums.State.PREPARE_FOR_HEARING;
import static uk.gov.hmcts.reform.fpl.enums.State.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN_SYSTEM_UPDATE;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CAFCASS;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CCD_LASOLICITOR;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CCD_SOLICITOR;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.GATEKEEPER;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.JUDICIARY;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.SYSTEM_UPDATE;

// Found and invoked by the config generator.
// The CaseData type parameter tells the generator which class represents your case model.
public class CCDConfig extends BaseCCDConfig<CaseData, State, UserRole> {

    protected String environment() {
        return "production";
    }

    // Builds the URL for a webhook based on the event.
    protected String webhookConvention(Webhook webhook, String eventId) {
        eventId = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, eventId);
        String path = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, webhook.toString());
        return "${CCD_DEF_CASE_SERVICE_BASE_URL}/callback/" + eventId + "/" + path;
    }

    @Override
    public void configure() {
        jurisdiction("PUBLICLAW", "Family Public Law", "Family Public Law desc");
        caseType("CARE_SUPERVISION_EPO", "Care, supervision and EPOs", "Care, supervision and emergency protection orders");
        setEnvironment(environment());
        setWebhookConvention(this::webhookConvention);

        // Describe the hierarchy of which roles go together.
        role(CCD_SOLICITOR, CCD_LASOLICITOR).has(LOCAL_AUTHORITY);
        role(JUDICIARY, GATEKEEPER).has(HMCTS_ADMIN);
        role(SYSTEM_UPDATE, BULK_SCAN, BULK_SCAN_SYSTEM_UPDATE).setApiOnly();

        // Disable AuthorisationCaseField generation for these roles.
        // TODO: complete configuration of permissions for these roles.
        role(CCD_SOLICITOR, CCD_LASOLICITOR).noCaseEventToField();

        // Events
        buildUniversalEvents();
        buildOpen();
        buildTransitions();

        // UI tabs and inputs.
        buildTabs();
        buildWorkBasketResultFields();
        buildWorkBasketInputFields();
        buildSearchResultFields();
        buildSearchInputFields();

    }

    private void buildSearchResultFields() {
        searchResultFields()
                .field(CaseData::getCaseName, "Case name")
                .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
                .stateField()
                .field(CaseData::getCaseLocalAuthority, "Local authority")
                .field("dateAndTimeSubmitted", "Date submitted");
    }

    private void buildSearchInputFields() {
        searchInputFields()
                .field(CaseData::getCaseLocalAuthority, "Local authority")
                .field(CaseData::getCaseName, "Case name")
                .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
                .caseReferenceField()
                .field(CaseData::getDateSubmitted, "Date submitted");
    }

    private void buildWorkBasketResultFields() {
        workBasketResultFields()
            .field(CaseData::getCaseName, "Case name")
            .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
            .stateField()
            .field(CaseData::getCaseLocalAuthority, "Local authority")
            .field("dateAndTimeSubmitted", "Date submitted")
            .field("evidenceHandled", "Supplementary evidence handled");
    }

    private void buildWorkBasketInputFields() {
        workBasketInputFields()
            .field(CaseData::getCaseLocalAuthority, "Local authority")
            .field(CaseData::getCaseName, "Case name")
            .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
            .caseReferenceField()
            .field(CaseData::getDateSubmitted, "Date submitted")
            .field("evidenceHandled", "Supplementary evidence handled");
    }

    private void buildTabs() {
        tab("HearingTab", "Hearings")
            .restrictedField(CaseData::getHearingDetails).exclude(CAFCASS)
            .restrictedField(CaseData::getHearing).exclude(LOCAL_AUTHORITY);

        tab("DraftOrdersTab", "Draft orders")
            .exclude(LOCAL_AUTHORITY, HMCTS_ADMIN, GATEKEEPER, JUDICIARY)
            .showCondition("standardDirectionOrder.orderStatus!=\"SEALED\" OR caseManagementOrder!=\"\" OR sharedDraftCMODocument!=\"\" OR cmoToAction!=\"\"")
            .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus!=\"SEALED\"")
            .field(CaseData::getSharedDraftCMODocument)
            .restrictedField(CaseData::getCaseManagementOrder_Judiciary).exclude(CAFCASS)
            .restrictedField(CaseData::getCaseManagementOrder).exclude(CAFCASS);

        tab("OrdersTab", "Orders")
            .exclude(LOCAL_AUTHORITY)
            .restrictedField(CaseData::getServedCaseManagementOrders).exclude(CAFCASS)
            .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus=\"SEALED\"")
            .field(CaseData::getOrders)
            .restrictedField(CaseData::getOrderCollection).exclude(CAFCASS);

        tab("CasePeopleTab", "People in the case")
            .exclude(LOCAL_AUTHORITY)
            .field(CaseData::getAllocatedJudge)
            .field(CaseData::getChildren1)
            .field(CaseData::getRespondents1)
            .field(CaseData::getApplicants)
            .field(CaseData::getSolicitor)
            .field(CaseData::getOthers)
            .restrictedField(CaseData::getRepresentatives).exclude(CAFCASS, LOCAL_AUTHORITY);

        tab("LegalBasisTab", "Legal basis")
            .exclude(LOCAL_AUTHORITY)
            .field(CaseData::getStatementOfService)
            .field(CaseData::getGroundsForEPO)
            .field(CaseData::getGrounds)
            .field(CaseData::getRisks)
            .field(CaseData::getFactorsParenting)
            .field(CaseData::getInternationalElement)
            .field(CaseData::getProceeding)
            .field(CaseData::getAllocationDecision)
            .field(CaseData::getAllocationProposal)
            .field(CaseData::getHearingPreferences);

        tab("DocumentsTab", "Documents")
            .exclude(LOCAL_AUTHORITY)
            .field(CaseData::getSocialWorkChronologyDocument)
            .field(CaseData::getSocialWorkStatementDocument)
            .field(CaseData::getSocialWorkAssessmentDocument)
            .field(CaseData::getSocialWorkCarePlanDocument)
            .field("standardDirectionsDocument")
            .field("otherCourtAdminDocuments")
            .field(CaseData::getSocialWorkEvidenceTemplateDocument)
            .field(CaseData::getThresholdDocument)
            .field(CaseData::getChecklistDocument)
            .field("courtBundle")
            .field(CaseData::getOtherSocialWorkDocuments)
            .field("submittedForm")
            .restrictedField(CaseData::getNoticeOfProceedingsBundle).exclude(CAFCASS)
            .field(CaseData::getC2DocumentBundle)
            .restrictedField("scannedDocuments").exclude(CAFCASS);

        tab("Confidential", "Confidential")
            .exclude(CAFCASS, LOCAL_AUTHORITY)
            .field(CaseData::getConfidentialChildren)
            .field(CaseData::getConfidentialRespondents)
            .field(CaseData::getConfidentialOthers);

        tab("PlacementTab", "Placement")
            .exclude(CAFCASS, LOCAL_AUTHORITY, HMCTS_ADMIN, GATEKEEPER, JUDICIARY)
            .field("placements")
            .field(CaseData::getPlacements)
            .field("placementsWithoutPlacementOrder");

        tab("SentDocumentsTab", "Documents sent to parties")
            .exclude(CAFCASS, LOCAL_AUTHORITY)
            .field("documentsSentToParties");

        tab("PaymentsTab", "Payment History")
            .restrictedField("paymentHistory").exclude(CAFCASS, LOCAL_AUTHORITY);

        tab("Notes", "Notes")
            .restrictedField(CaseData::getCaseNotes).exclude(CAFCASS, LOCAL_AUTHORITY);
    }

    private void buildUniversalEvents() {
        event("addFamilyManCaseNumber")
            .forAllStates()
            .name("Add case number")
            .explicitGrants()
            .grant("CRU", HMCTS_ADMIN)
            .aboutToSubmitWebhook("add-case-number")
            .submittedWebhook()
            .fields()
            .optional(CaseData::getFamilyManCaseNumber);

        event("allocatedJudge")
            .forAllStates()
            .name("Allocated Judge")
            .description("Add allocated judge to a case")
            .grantHistoryOnly(LOCAL_AUTHORITY)
            .grant("CRU", JUDICIARY, HMCTS_ADMIN, GATEKEEPER)
            .grant("R", CAFCASS)
            .fields()
            .page("AllocatedJudge")
                .field(CaseData::getAllocatedJudge).complex()
                    .mandatory(Judge::getJudgeTitle)
                    .mandatory(Judge::getOtherTitle)
                    .mandatory(Judge::getJudgeLastName)
                    .mandatory(Judge::getJudgeFullName);

    }

    private void buildTransitions() {
        event("populateSDO")
                .forStateTransition(Submitted, Gatekeeping)
                .name("Populate standard directions")
                .displayOrder(14) // TODO - necessary?
                .explicitGrants()
                .grant("C", UserRole.SYSTEM_UPDATE)
                .fields()
                    .optional(CaseData::getAllParties)
                    .optional(CaseData::getLocalAuthorityDirections)
                    .optional(CaseData::getRespondentDirections)
                    .optional(CaseData::getCafcassDirections)
                    .optional(CaseData::getOtherPartiesDirections)
                    .optional(CaseData::getCourtDirections);
    }

    private void buildOpen() {
        // Local Authority can view the history of all events in the Open state.
        grantHistory(Open,LOCAL_AUTHORITY);
        event("openCase")
                .initialState(Open)
                .name("Start application")
                .description("Create a new case – add a title")
                .grant("CRU", LOCAL_AUTHORITY)
                .aboutToSubmitWebhook("case-initiation")
                .submittedWebhook()
                .retries(1,2,3,4,5)
                .fields()
                    .optional(CaseData::getCaseName);

    }
}
