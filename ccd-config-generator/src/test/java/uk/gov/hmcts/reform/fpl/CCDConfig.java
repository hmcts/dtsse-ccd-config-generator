package uk.gov.hmcts.reform.fpl;


import static uk.gov.hmcts.ccd.sdk.api.Permission.C;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.ccd.sdk.api.Permission.R;
import static uk.gov.hmcts.ccd.sdk.api.SortOrder.*;
import static uk.gov.hmcts.reform.fpl.enums.State.Deleted;
import static uk.gov.hmcts.reform.fpl.enums.State.Gatekeeping;
import static uk.gov.hmcts.reform.fpl.enums.State.Open;
import static uk.gov.hmcts.reform.fpl.enums.State.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.BULK_SCAN_SYSTEM_UPDATE;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CAFCASS;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CASE_ACCESS_APPROVER;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.CCD_SOLICITOR;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.SYSTEM_UPDATE;


import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.type.Organisation;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.HearingPreferences;
import uk.gov.hmcts.reform.fpl.model.Judge;
import uk.gov.hmcts.reform.fpl.model.LocationPreferences;

// Found and invoked by the config generator.
// The CaseData type parameter tells the generator which class represents your case model.
@Component
public class CCDConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<CaseData, State, UserRole> {

  private ConfigBuilder<CaseData, State, UserRole> builder;

  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    this.builder = builder;
    builder.addPreEventHook(RetiredFields::migrate);
    builder.setCallbackHost("${CCD_DEF_CASE_SERVICE_BASE_URL}");
    builder.jurisdiction("PUBLICLAW", "Family Public Law", "Family Public Law desc");
    builder.omitHistoryForRoles(SYSTEM_UPDATE);
    builder.caseType("CARE_SUPERVISION_EPO", "Care, supervision and EPOs", "Care, supervision and emergency protection orders");

    builder.grant(Open, Set.of(R), LOCAL_AUTHORITY);
    builder.shutterService(CASE_ACCESS_APPROVER);

    // Events
    buildUniversalEvents();
    buildAttachScannedDocEvent();
    buildHandleEvidenceEvent();
    buildOpen();
    buildTransitions();

    // UI tabs and inputs.
    buildTabs();
    buildWorkBasketResultFields();
    buildWorkBasketInputFields();
    buildSearchResultFields();
    buildSearchInputFields();
    buildSearchCasesFields();

    builder.event("checkReady")
      .forStateTransition(EnumSet.allOf(State.class), EnumSet.of(Gatekeeping, Submitted))
      .name("Add case notes")
      .grant(CRU, CAFCASS)
      .grant(R, LOCAL_AUTHORITY)
        .aboutToSubmitCallback(this::checkReadyAboutToSubmit);

    builder.event("addNotes")
        .forStates(Gatekeeping, Submitted)
        .name("Add case notes")
        .grant(CRU, HMCTS_ADMIN)
        .grant(R, LOCAL_AUTHORITY)
        .grant(new SolicitorAccess())
        .fields()
        .optional(CaseData::getCaseNotes)
        .complex(CaseData::getHearingPreferences)
          .label("hearingPrefs", "Hearing Preferences")
          .optional(HearingPreferences::getWelsh)
          .optionalWithoutDefaultValue(HearingPreferences::getInterpreter, "hearingPreferencesWelsh=\"yes\"", "Interpreter required", true)
          .complex(HearingPreferences::getLocationPreferences)
            .optional(LocationPreferences::getLocal)
            .done()
          .complex(HearingPreferences::getOrganisationPolicy, null, "Event label", "Event hint")
            .complex(OrganisationPolicy::getOrganisation)
              .mandatory(Organisation::getOrganisationId)
              .done()
            .optional(OrganisationPolicy::getOrgPolicyCaseAssignedRole, null, CCD_SOLICITOR)
            .optional(OrganisationPolicy::getOrgPolicyReference, null, null, "Org ref", "Sol org ref")
            .done()
          .done()
        .optional(CaseData::getCaseName)
        .optionalWithLabel(CaseData::getGatekeeperEmail, "Gate keeper email")
        .mandatoryWithoutDefaultValue(CaseData::getAllocatedJudge, "hearingPreferencesWelsh=\"yes\"", "Judge is bilingual", true)
        .mandatoryWithLabel(CaseData::getCaseLocalAuthority, "Please enter a case local authority");
  }

  private AboutToStartOrSubmitResponse<CaseData, State> checkReadyAboutToSubmit(CaseDetails<CaseData, State> details,
                                                                                CaseDetails<CaseData, State> beforeDetails) {
    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .state(details.getData().getAllocatedJudge().getJudgeFullName().equals("judge") ? Submitted : Gatekeeping)
        .data(details.getData())
        .build();
  }

  private void buildSearchResultFields() {
    builder.searchResultFields()
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .field("hearingPreferencesWelsh", "Is in Welsh")
        .stateField()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field("dateAndTimeSubmitted", "Date submitted");
  }

  private void buildSearchInputFields() {
    builder.searchInputFields()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .field("hearingPreferencesWelsh", "Is in Welsh")
        .caseReferenceField()
        .field("allocatedJudge", "Allocated Judge", "judgeTitle", "hearingPreferencesWelsh=\"no\"")
        .field(CaseData::getDateSubmitted, "Date submitted");
  }

  private void buildSearchCasesFields() {
    builder.searchCasesFields()
      .field(CaseData::getAllocatedJudge, "Allocated Judge")
      .caseReferenceField();
  }

  private void buildWorkBasketResultFields() {
    builder.workBasketResultFields()
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .field("hearingPreferencesWelsh", "Is in Welsh")
        .stateField()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field("dateAndTimeSubmitted", "Date submitted", null, null, "#DATETIMEDISPLAY(d  MMMM yyyy)", FIRST.DESCENDING)
        .field("evidenceHandled", "Supplementary evidence handled", SECOND.ASCENDING);
  }

  private void buildWorkBasketInputFields() {
    builder.workBasketInputFields()
        .field(CaseData::getCaseLocalAuthority, "Local authority")
        .field(CaseData::getCaseName, "Case name")
        .field(CaseData::getFamilyManCaseNumber, "FamilyMan case number")
        .field("hearingPreferencesWelsh", "Is in Welsh")
        .caseReferenceField()
        .field(CaseData::getDateSubmitted, "Date submitted", "", "#DATETIMEDISPLAY(d  MMMM yyyy)")
        .field("evidenceHandled", "Supplementary evidence handled")
        .field("internationalElement", "int el", "issues", "hearingPreferencesWelsh=\"no\"");
  }

  private void buildTabs() {
    builder.tab("DraftOrdersTab", "Draft orders")
        .showCondition("standardDirectionOrder.orderStatus!=\"SEALED\" OR caseManagementOrder!=\"\" OR sharedDraftCMODocument!=\"\" OR cmoToAction!=\"\"")
        .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus!=\"SEALED\"")
        .field(CaseData::getSharedDraftCMODocument)
        .field(CaseData::getDateSubmitted, null, "#DATETIMEDISPLAY(d  MMMM yyyy)");

    builder.tab("OrdersTab", "Orders")
        .field(CaseData::getStandardDirectionOrder, "standardDirectionOrder.orderStatus=\"SEALED\"")
        .field(CaseData::getOrders);

    builder.tab("CasePeopleTab", "People in the case")
        .field(CaseData::getAllocatedJudge)
        .field(CaseData::getChildren1)
        .field(CaseData::getRespondents1)
        .field(CaseData::getApplicants)
        .field(CaseData::getSolicitor)
        .field(CaseData::getOthers);

    builder.tab("LegalBasisTab", "Legal basis")
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
        .field(CaseData::getC2DocumentBundle);

    builder.tab("Confidential", "Confidential")
        .field(CaseData::getConfidentialChildren)
        .field(CaseData::getConfidentialRespondents)
        .field(CaseData::getConfidentialOthers);

    builder.tab("PlacementTab", "Placement")
        .field("placements")
        .field(CaseData::getPlacements)
        .field("placementsWithoutPlacementOrder");

    builder.tab("SentDocumentsTab", "Documents sent to parties")
        .field("documentsSentToParties");

    builder.tab("TabWithUserRoles", "Test multiple tabs get created for each user")
        .forRoles(BULK_SCAN, BULK_SCAN_SYSTEM_UPDATE)
        .field("hearingPreferencesLocationPreferencesLocal")
        .field("hearingPreferencesLocationPreferencesOnline");

    builder.tab("PaymentHistory", "Payment History")
      .field(CaseData::getPaymentHistoryField);
  }

  private void buildUniversalEvents() {
    builder.event("allocatedJudge")
        .forAllStates()
        .name("Allocated Judge")
        .description("Add allocated judge to a case")
        .grantHistoryOnly(LOCAL_AUTHORITY)
        .grant(CRU, HMCTS_ADMIN)
        .grant(R, CAFCASS)
        .showCondition("allocatedJudge=\"\"")
        .fields()
        .page("AllocatedJudge")
        .complex(CaseData::getOrganisationPolicy, null, "Event label", "Event hint")
          .complex(OrganisationPolicy::getOrganisation)
            .mandatory(Organisation::getOrganisationId)
            .mandatoryNoSummary(Organisation::getOrganisationName, null, "Organisation Name")
          .done()
          .optional(OrganisationPolicy::getOrgPolicyCaseAssignedRole, null, CCD_SOLICITOR)
          .optional(OrganisationPolicy::getOrgPolicyReference, null, null, "Org ref", "Sol org ref")
        .done()
        .label("allocatedJudgeLabel", "Allocated Judge")
        .complex(CaseData::getAllocatedJudge, false)
          .mandatory(Judge::getJudgeTitle)
          .mandatory(Judge::getOtherTitle)
          .mandatory(Judge::getJudgeLastName)
          .mandatory(Judge::getJudgeFullName)
          .optionalNoSummary(Judge::getJudgeEmailId, null, "Judge email id")
        .done()
        .optional(CaseData::getCaseName, null, null, "Allocated case name", "A hint")
        .page("<Notes>", this::checkCaseNotes)
          .mandatory(CaseData::getCaseNotes);
  }

  private void buildAttachScannedDocEvent() {
    builder.attachScannedDocEvent()
      .forAllStates()
      .grant(CRU, SYSTEM_UPDATE)
      .fields()
      .page("attachScannedDocs")
      .pageLabel("Correspondence")
      .mandatory(CaseData::getScannedDocuments)
      .mandatory(CaseData::getEvidenceHandled)
      .mandatory(CaseData::getDateOfIssue, null, null, "Date of issue", "A hint", "#DATETIMEDISPLAY(d  MMMM yyyy)")
      .mandatoryWithDisplayContextParameter(CaseData::getDateSubmitted, null, "#DATETIMEDISPLAY(d  MMMM yyyy)");
  }

  private void buildHandleEvidenceEvent() {
    builder.handleSupplementaryEvent()
      .forAllStates()
      .grant(CRU, SYSTEM_UPDATE)
      .fields()
      .page("handleEvidence")
      .pageLabel("Correspondence")
      .mandatory(CaseData::getEvidenceHandled);
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
        .grant(CRU, LOCAL_AUTHORITY, HMCTS_ADMIN)
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
