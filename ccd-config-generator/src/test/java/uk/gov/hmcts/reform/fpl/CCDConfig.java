package uk.gov.hmcts.reform.fpl;


import static uk.gov.hmcts.ccd.sdk.api.Permission.C;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.ccd.sdk.api.Permission.R;
import static uk.gov.hmcts.reform.fpl.enums.State.Deleted;
import static uk.gov.hmcts.reform.fpl.enums.State.Gatekeeping;
import static uk.gov.hmcts.reform.fpl.enums.State.Open;
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

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.type.Organisation;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.Judge;

// Found and invoked by the config generator.
// The CaseData type parameter tells the generator which class represents your case model.
@Component
public class CCDConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<CaseData, State, UserRole> {

  private ConfigBuilder<CaseData, State, UserRole> builder;

  protected String environment() {
        return "production";
    }

  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    this.builder = builder;
    builder.setCallbackHost("${CCD_DEF_CASE_SERVICE_BASE_URL}");
    builder.setEnvironment(environment());
    builder.jurisdiction("PUBLICLAW", "Family Public Law", "Family Public Law desc");
    builder.caseType("CARE_SUPERVISION_EPO", "Care, supervision and EPOs", "Care, supervision and emergency protection orders");

    // Admin gets CRU on everything in Open state.
    builder.grant(Open, CRU, HMCTS_ADMIN);

    // Local Authority can view the history of all events in the Open state.
    builder.grantHistory(Open, LOCAL_AUTHORITY);

    // Describe the hierarchy of which roles go together.
    builder.role(CCD_SOLICITOR, CCD_LASOLICITOR).has(LOCAL_AUTHORITY);
    builder.role(JUDICIARY, GATEKEEPER).has(HMCTS_ADMIN);
    builder.role(SYSTEM_UPDATE, BULK_SCAN, BULK_SCAN_SYSTEM_UPDATE).setApiOnly();

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

    builder.event("addNotes")
        .forStates(Gatekeeping, Submitted)
        .name("Add case notes")
        .fields()
        .optional(CaseData::getCaseNotes);
  }

  private void buildSearchResultFields() {
    builder.searchResultFields()
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .stateField()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field("dateAndTimeSubmitted", "Date submitted");
  }

  private void buildSearchInputFields() {
    builder.searchInputFields()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .caseReferenceField()
        .field(CaseData::getDateSubmitted, "Date submitted");
  }

  private void buildWorkBasketResultFields() {
    builder.workBasketResultFields()
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .stateField()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field("dateAndTimeSubmitted", "Date submitted")
        .field("evidenceHandled", "Supplementary evidence handled");
  }

  private void buildWorkBasketInputFields() {
    builder.workBasketInputFields()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .caseReferenceField()
        .field(CaseData::getDateSubmitted, "Date submitted")
        .field("evidenceHandled", "Supplementary evidence handled");
  }

  private void buildTabs() {
    builder.tab("HearingTab", "Hearings")
        .restrictedField(CaseData::getHearingDetails).exclude(CAFCASS)
        .restrictedField(CaseData::getHearing).exclude(LOCAL_AUTHORITY);

    builder.tab("DraftOrdersTab", "Draft orders")
        .exclude(LOCAL_AUTHORITY, HMCTS_ADMIN, GATEKEEPER, JUDICIARY)
        .showCondition("standardDirectionOrder.orderStatus!=\"SEALED\" OR caseManagementOrder!=\"\" OR sharedDraftCMODocument!=\"\" OR cmoToAction!=\"\"")
        .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus!=\"SEALED\"")
        .field(CaseData::getSharedDraftCMODocument)
        .restrictedField(CaseData::getCaseManagementOrder_Judiciary).exclude(CAFCASS)
        .restrictedField(CaseData::getCaseManagementOrder).exclude(CAFCASS);

    builder.tab("OrdersTab", "Orders")
        .exclude(LOCAL_AUTHORITY)
        .restrictedField(CaseData::getServedCaseManagementOrders).exclude(CAFCASS)
        .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus=\"SEALED\"")
        .field(CaseData::getOrders)
        .restrictedField(CaseData::getOrderCollection).exclude(CAFCASS);

    builder.tab("CasePeopleTab", "People in the case")
        .exclude(LOCAL_AUTHORITY)
        .field(CaseData::getAllocatedJudge)
        .field(CaseData::getChildren1)
        .field(CaseData::getRespondents1)
        .field(CaseData::getApplicants)
        .field(CaseData::getSolicitor)
        .field(CaseData::getOthers)
        .restrictedField(CaseData::getRepresentatives).exclude(CAFCASS, LOCAL_AUTHORITY);

    builder.tab("LegalBasisTab", "Legal basis")
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

    builder.tab("DocumentsTab", "Documents")
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

    builder.tab("Confidential", "Confidential")
        .exclude(CAFCASS, LOCAL_AUTHORITY)
        .field(CaseData::getConfidentialChildren)
        .field(CaseData::getConfidentialRespondents)
        .field(CaseData::getConfidentialOthers);

    builder.tab("PlacementTab", "Placement")
        .exclude(CAFCASS, LOCAL_AUTHORITY, HMCTS_ADMIN, GATEKEEPER, JUDICIARY)
        .field("placements")
        .field(CaseData::getPlacements)
        .field("placementsWithoutPlacementOrder");

    builder.tab("SentDocumentsTab", "Documents sent to parties")
        .exclude(CAFCASS, LOCAL_AUTHORITY)
        .field("documentsSentToParties");

    builder.tab("PaymentsTab", "Payment History")
        .restrictedField("paymentHistory").exclude(CAFCASS, LOCAL_AUTHORITY);

    builder.tab("Notes", "Notes")
        .restrictedField(CaseData::getCaseNotes).exclude(CAFCASS, LOCAL_AUTHORITY);
  }

  private void buildUniversalEvents() {
    builder.event("allocatedJudge")
        .forAllStates()
        .name("Allocated Judge")
        .description("Add allocated judge to a case")
        .grantHistoryOnly(LOCAL_AUTHORITY)
        .grant(CRU, JUDICIARY, HMCTS_ADMIN, GATEKEEPER)
        .grant(R, CAFCASS)
        .fields()
        .page("AllocatedJudge")
        .complex(CaseData::getOrganisationPolicy)
          .complex(OrganisationPolicy::getOrganisation)
            .mandatory(Organisation::getOrganisationId)
          .done()
          .optional(OrganisationPolicy::getOrgPolicyCaseAssignedRole, null, CCD_SOLICITOR)
          .optional(OrganisationPolicy::getOrgPolicyReference)
        .done()
        .complex(CaseData::getAllocatedJudge, false)
          .mandatory(Judge::getJudgeTitle)
          .mandatory(Judge::getOtherTitle)
          .mandatory(Judge::getJudgeLastName)
          .mandatory(Judge::getJudgeFullName)
        .done()
        .page("<Notes>", this::checkCaseNotes)
          .mandatory(CaseData::getCaseNotes);
  }

  private AboutToStartOrSubmitResponse<CaseData, State> checkCaseNotes(
      CaseDetails<CaseData, State> caseDataStateCaseDetails,
      CaseDetails<CaseData, State> caseDataStateCaseDetailsBefore) {
    throw new RuntimeException();
  }

  private void buildTransitions() {
    builder.event("populateSDO")
        .forStateTransition(Submitted, Gatekeeping)
        .name("Populate standard directions")
        .explicitGrants()
        .grant(C, UserRole.SYSTEM_UPDATE)
        .fields()
        .page("1", this::sdoMidEvent)
        .optional(CaseData::getAllParties)
        .optional(CaseData::getLocalAuthorityDirections)
        .optional(CaseData::getRespondentDirections)
        .optional(CaseData::getCafcassDirections)
        .optional(CaseData::getOtherPartiesDirections)
        .optional(CaseData::getCourtDirections);
  }

  private AboutToStartOrSubmitResponse<CaseData, State> sdoMidEvent(
      CaseDetails<CaseData, State> caseDataStateCaseDetails,
      CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    return AboutToStartOrSubmitResponse.<CaseData, State>builder().build();
  }

  private void buildOpen() {
    builder.event("openCase")
        .initialState(Open)
        .name("Start application")
        .description("Create a new case – add a title")
        .grant(CRU, LOCAL_AUTHORITY)
        .aboutToSubmitCallback(this::aboutToSubmit)
        .submittedCallback(this::submitted)
        .retries(1,2,3,4,5)
        .fields()
        .optional(CaseData::getCaseName);

    builder.event("deleted")
        .forState(Deleted)
        .name("Deleted only");
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> caseDataStateCaseDetails,
      CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    throw new RuntimeException();
  }

  private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDataStateCaseDetails,
                                              CaseDetails<CaseData, State> caseDataStateCaseDetails1) {
    throw new RuntimeException();
  }
}
