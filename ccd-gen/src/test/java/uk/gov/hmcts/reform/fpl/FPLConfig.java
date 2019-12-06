package uk.gov.hmcts.reform.fpl;


import ccd.sdk.types.CCDConfig;
import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.DisplayContext;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class FPLConfig implements CCDConfig<CaseData> {

    @Override
    public void configure(ConfigBuilder<CaseData> builder) {

        builder.caseType("CARE_SUPERVISION_EPO");
        buildInitialEvents(builder);
        buildSharedEvents(builder, "Submitted", "");
        buildSharedEvents(builder, "Gatekeeping", "Gatekeeping");
        buildSharedEvents(builder, "PREPARE_FOR_HEARING", "-PREPARE_FOR_HEARING");
        buildPrepareForHearingEvents(builder);
        buildUniversalEvents(builder);
        buildSubmittedEvents(builder);
        buildGatekeepingEvents(builder);
        buildStandardDirections(builder, "Submitted", "");
        buildStandardDirections(builder, "Gatekeeping", "AfterGatekeeping");
        buildTransitions(builder);
    }

    private void buildTransitions(ConfigBuilder<CaseData> builder) {
        builder.event("submitApplication")
                .name("Submit application")
                .description("Submit application")
                .preState("Open")
                .postState("Submitted")
                .endButtonLabel("Submit")
                .aboutToStartURL("/case-submission/about-to-start")
                .aboutToSubmitURL("/case-submission/about-to-submit")
                .submittedURL("/case-submission/submitted")
                .midEventURL("/case-submission/mid-event")
                .retries("1,2,3,4,5")
                .label("submissionConsentLabel", "")
                .field("submissionConsent", DisplayContext.Mandatory);

        builder.event("populateSDO")
                .name("Populate standard directions")
                .description("Populate standard directions")
                .preState("Submitted")
                .postState("Gatekeeping")
                .pageLabel(" ")
                    .field(CaseData::getAllParties, DisplayContext.Optional)
                    .field(CaseData::getLocalAuthorityDirections, DisplayContext.Optional)
                    .field(CaseData::getRespondentDirections, DisplayContext.Optional)
                    .field(CaseData::getCafcassDirections, DisplayContext.Optional)
                    .field(CaseData::getOtherPartiesDirections, DisplayContext.Optional)
                    .field(CaseData::getCourtDirections, DisplayContext.Optional);

        builder.event("deleteApplication")
                .preState("Open")
                .postState("Deleted")
                .name("Delete an application")
                .aboutToSubmitURL("/case-deletion/about-to-submit")
                .endButtonLabel("Delete application")
                .field("deletionConsent", DisplayContext.Mandatory);
    }

    private void buildGatekeepingEvents(ConfigBuilder<CaseData> builder) {
        builder.event("otherAllocationDecision")
                .name("Allocation decision")
                .description("Entering other proceedings and allocation proposals")
                .aboutToStartURL("/allocation-decision/about-to-start")
                .aboutToSubmitURL("/allocation-decision/about-to-submit")
                .retries("1,2,3,4,5")
                .field(CaseData::getAllocationDecision, DisplayContext.Mandatory, true)
                .forState("Gatekeeping");

        builder.event("draftSDO")
                .name("Draft standard directions")
                .description("Draft standard directions")
                .aboutToStartURL("/draft-standard-directions/about-to-start")
                .aboutToSubmitURL("/draft-standard-directions/about-to-submit")
                .midEventURL("/draft-standard-directions/mid-event")
                .submittedURL("/draft-standard-directions/submitted")
                .forState("Gatekeeping")
                .page("judgeAndLegalAdvisor")
                    .field(CaseData::getJudgeAndLegalAdvisor, DisplayContext.Optional)
                .page("allPartiesDirections")
                    .field().id(CaseData::getHearingDetails).showCondition("hearingDate=\"DO_NOT_SHOW\"").context(DisplayContext.ReadOnly).done()
                    .field().id("hearingDate").context(DisplayContext.ReadOnly).showCondition("hearingDetails.startDate=\"\"").context(DisplayContext.ReadOnly).done()
                    .field(CaseData::getAllParties)
                    .field(CaseData::getAllPartiesCustom)
                .page("localAuthorityDirections")
                    .field(CaseData::getLocalAuthorityDirections)
                    .field(CaseData::getLocalAuthorityDirectionsCustom)
                .page("parentsAndRespondentsDirections")
                    .field(CaseData::getRespondentDirections)
                    .field(CaseData::getRespondentDirectionsCustom)
                .page("cafcassDirections")
                    .field(CaseData::getCafcassDirections)
                    .field(CaseData::getCafcassDirectionsCustom)
                .page("otherPartiesDirections")
                    .field(CaseData::getOtherPartiesDirections)
                    .field(CaseData::getOtherPartiesDirectionsCustom)
                .page("courtDirections")
                    .field(CaseData::getCourtDirections)
                    .field(CaseData::getCourtDirectionsCustom)
                .page("documentReview")
                    .field(CaseData::getStandardDirectionOrder);
    }

    private void buildStandardDirections(ConfigBuilder<CaseData> builder, String state, String suffix) {
        builder.event("uploadStandardDirections" + suffix)
                .name("Documents")
                .description("Upload standard directions")
                .label("standardDirectionsLabel", "Upload standard directions and other relevant documents, for example the C6 Notice of Proceedings or C9 statement of service.")
                .label("standardDirectionsTitle", "## 1. Standard directions")
                .field("standardDirectionsDocument", DisplayContext.Optional)
                .field("otherCourtAdminDocuments", DisplayContext.Optional)
                .forState(state);
    }

    private void buildSubmittedEvents(ConfigBuilder<CaseData> builder) {
        builder.event("addFamilyManCaseNumber")
                .name("Add case number")
                .description("Add case number")
                .forState("Submitted")
                .field(CaseData::getFamilyManCaseNumber, DisplayContext.Optional);

        builder.event("addStatementOfService")
                .forState("Submitted")
                .name("Add statement of service (c9)")
                .description("Add statement of service")
                .aboutToStartURL("/statement-of-service/about-to-start")
                .label("c9Declaration", "If you send documents to a party's solicitor or a children's guardian, give their details")
                .field(CaseData::getStatementOfService, DisplayContext.Mandatory, true)
                .label("serviceDeclarationLabel", "Declaration" )
                .field("serviceConsent", DisplayContext.Mandatory);

        builder.event("createC21Order")
                .forState("Submitted")
                .name("Create an order")
                .description("Create an order")
                .aboutToStartURL("/create-order/about-to-start")
                .aboutToSubmitURL("/create-order/about-to-submit")
                .submittedURL("/create-order/about-to-submit")
                .midEventURL("/create-order/mid-event")
                .page("OrderInformation")
                    .field().id(CaseData::getC21Order).showSummary(true).done()
                .page("JudgeInformation")
                    .field().id(CaseData::getJudgeAndLegalAdvisor).showSummary(true);

        // TODO - dedupe!
        builder.event("createC21OrderGatekeeping")
                .forState("Submitted")
                .name("Create an order")
                .description("Create an order")
                .aboutToStartURL("/create-order/about-to-start")
                .aboutToSubmitURL("/create-order/about-to-submit")
                .submittedURL("/create-order/about-to-submit")
                .midEventURL("/create-order/mid-event")
                .page("OrderInformation")
                .field().id(CaseData::getC21Order).showSummary(true).done()
                .page("JudgeInformation")
                .field().id(CaseData::getJudgeAndLegalAdvisor).showSummary(true);

        builder.event("uploadDocumentsAfterSubmission")
                .forState("Submitted")
                .name("Documents")
                .description("Only here for backwards compatibility with case history");
    }

    private void buildUniversalEvents(ConfigBuilder<CaseData> builder) {
        builder.event("uploadDocuments").forState("*")
                .name("Documents")
                .description("Upload documents")
                .midEventURL("/upload-documents/mid-event")
                .label("uploadDocuments_paragraph_1", "You must upload these documents if possible. Give the reason and date you expect to provide it if you don’t have a document yet.")
                .field(CaseData::getSocialWorkChronologyDocument, DisplayContext.Optional)
                .field(CaseData::getSocialWorkStatementDocument, DisplayContext.Optional)
                .field(CaseData::getSocialWorkAssessmentDocument, DisplayContext.Optional)
                .field(CaseData::getSocialWorkCarePlanDocument, DisplayContext.Optional)
                .field(CaseData::getSocialWorkEvidenceTemplateDocument, DisplayContext.Optional)
                .field(CaseData::getThresholdDocument, DisplayContext.Optional)
                .field(CaseData::getChecklistDocument, DisplayContext.Optional)
                .field("[STATE]", DisplayContext.ReadOnly, "courtBundle = \"DO_NOT_SHOW\"")
                .field("courtBundle", DisplayContext.Optional, "[STATE] != \"Open\"")
                .label("documents_socialWorkOther_border_top", "---------------------------------")
                .field(CaseData::getOtherSocialWorkDocuments, DisplayContext.Optional)
                .label("documents_socialWorkOther_border_bottom", "---------------------------------");
    }

    private void buildPrepareForHearingEvents(ConfigBuilder<CaseData> builder) {
        builder.event("uploadOtherCourtAdminDocuments-PREPARE_FOR_HEARING")
                .forState("PREPARE_FOR_HEARING")
                .field("otherCourtAdminDocuments", DisplayContext.Optional);

        builder.event("draftCMO")
                .name("Draft CMO")
                .name("Draft Case Management Order")
                .forState("PREPARE_FOR_HEARING")
                .page("hearingDate")
                    .field("cmoHearingDateList", DisplayContext.Mandatory)
                .page("allPartiesDirections")
                    .label("allPartiesLabelCMO", "## For all parties")
                    .label("allPartiesPrecedentLabelCMO", "Add completed directions from the precedent library or your own template.")
                    .field(CaseData::getAllPartiesCustom)
                .page("localAuthorityDirections")
                     .label("localAuthorityDirectionsLabelCMO", "## For the local authority")
                     .field(CaseData::getLocalAuthorityDirectionsCustom)
                .page(2)
                     .label("respondentsDirectionLabelCMO", "For the parents or respondents")
                     .label("respondentsDropdownLabelCMO", " ")
                     .field(CaseData::getRespondentDirectionsCustom)
                .page("cafcassDirections")
                     .label("cafcassDirectionsLabelCMO", "## For Cafcass")
                     .field(CaseData::getCafcassDirectionsCustom)
                .page(3)
                     .label("otherPartiesDirectionLabelCMO", "## For other parties")
                     .label("otherPartiesDropdownLabelCMO", " ")
                     .field(CaseData::getOtherPartiesDirectionsCustom)
                .page("courtDirections")
                     .label("courtDirectionsLabelCMO", "## For the court")
                     .field(CaseData::getCourtDirectionsCustom)
                .page(5)
                     .label("orderBasisLabel", "## Basis of order")
                     .label("addRecitalLabel", "## Add recital")
                     .field("recitals", DisplayContext.Optional)
                .page("schedule")
                     .field("schedule", DisplayContext.Mandatory);

        builder.event("COMPLY_LOCAL_AUTHORITY")
                .name("Comply with directions")
                .description("Allows Local Authority user access to comply with their directions as well as ones for all parties")
                .forState("PREPARE_FOR_HEARING")
                .pageLabel(" ")
                    .field(CaseData::getLocalAuthorityDirections);

        builder.event("COMPLY_CAFCASS")
                .name("Comply with directions")
                .description("Allows Cafcass user access to comply with their directions as well as ones for all parties")
                .forState("PREPARE_FOR_HEARING")
                .pageLabel(" ")
                    .field(CaseData::getCafcassDirections);

        // courtDirectionsCustom is used here to prevent Gatekeeper inadvertently getting C and D permissions in draftSDO journey on courtDirections. We do not want them to able to create/delete directions"
        builder.event("COMPLY_COURT")
                .name("Comply with directions")
                .description("Event gives Court user access to comply with their directions as well as all parties")
                .forState("PREPARE_FOR_HEARING")
                .pageLabel(" ")
                    .field(CaseData::getCourtDirectionsCustom);

    }

    private void buildSharedEvents(ConfigBuilder<CaseData> builder, String state, String suffix) {
        builder.event("hearingBookingDetails" + suffix)
                .name("Add hearing details")
                .description("Add hearing booking details to a case")
                .midEventURL("/add-hearing-bookings/mid-event")
                .field().id(CaseData::getHearingDetails).showSummary(true).done()
                .forState(state);
        builder.event("uploadC2" + suffix)
                .name("Upload a C2")
                .description("Upload a c2 to the case")
                .field(CaseData::getTemporaryC2Document)
                .midEventURL("/upload-c2/mid-event")
                .forState(state);
        builder.event("sendToGatekeeper" + suffix)
                .name("Send to gatekeeper")
                .description("Send email to gatekeeper")
                .label("gateKeeperLabel", "Let the gatekeeper know there's a new case")
                .field(CaseData::getGatekeeperEmail, DisplayContext.Mandatory)
                .forState(state);
        builder.event("amendChildren" + suffix)
                .name("Children")
                .description("Amending the children for the case")
                .field(CaseData::getChildren1, DisplayContext.Optional)
                .forState(state);
        builder.event("amendRespondents" + suffix)
                .name("Respondents")
                .description("Amending the respondents for the case")
                .field(CaseData::getRespondents1, DisplayContext.Optional)
                .forState(state);
        builder.event("amendOthers" + suffix)
                .name("Others to be given notice")
                .description("Amending others for the case")
                .field(CaseData::getOthers, DisplayContext.Optional)
                .forState(state);
        builder.event("amendInternationalElement" + suffix)
                .name("International element")
                .description("Amending the international element")
                .field(CaseData::getInternationalElement, DisplayContext.Optional)
                .forState(state);
        builder.event("amendOtherProceedings" + suffix)
                .name("Other proceedings")
                .description("Amending other proceedings and allocation proposals")
                .midEventURL("/enter-other-proceedings/mid-event")
                .field(CaseData::getProceeding, DisplayContext.Optional)
                .forState(state);
        builder.event("amendAttendingHearing" + suffix)
                .name("Attending the hearing")
                .description("Amend extra support needed for anyone to take part in hearing")
                .field(CaseData::getHearingPreferences, DisplayContext.Optional)
                .forState(state);
        builder.event("createNoticeOfProceedings" + suffix)
                .name("Create notice of proceedings")
                .description("Create notice of proceedings")
                .label("proceedingLabel", "## Other proceedings 1")
                .field().id(CaseData::getNoticeOfProceedings).showSummary(true).done()
                .forState(state);
    }

    private void buildInitialEvents(ConfigBuilder<CaseData> builder) {
        builder.event("openCase")
                .preState(null)
                .postState("Open")
                .name("Start application")
                .description("Create a new case – add a title")
                .aboutToSubmitURL("/case-initiation/about-to-submit")
                .submittedURL("/case-initiation/submitted")
                .retries("1,2,3,4,5")
                .field(CaseData::getCaseName, DisplayContext.Optional);

        builder.event("ordersNeeded").forState("Open")
                .name("Orders and directions needed")
                .description("Selecting the orders needed for application")
                .aboutToSubmitURL("/orders-needed/about-to-submit")
                .field(CaseData::getOrders, DisplayContext.Optional);

        builder.event("hearingNeeded").forState("Open")
                .name("Hearing needed")
                .description("Selecting the hearing needed for application")
                .field(CaseData::getHearing, DisplayContext.Optional);

        builder.event("enterChildren").forState("Open")
                .name("Children")
                .description("Entering the children for the case")
                .field(CaseData::getChildren1, DisplayContext.Optional)
                .aboutToStartURL("/enter-children/about-to-start")
                .aboutToSubmitURL("/enter-children/about-to-submit");

        builder.event("enterRespondents").forState("Open")
                .name("Respondents")
                .description("Entering the respondents for the case")
                .aboutToStartURL("/enter-respondents/about-to-start")
                .aboutToSubmitURL("/enter-respondents/about-to-submit")
                .midEventURL("/enter-respondents/mid-event")
                .field(CaseData::getRespondents1, DisplayContext.Optional);

        builder.event("enterApplicant").forState("Open")
                .name("Applicant")
                .description("Entering the applicant for the case")
                .aboutToStartURL("/enter-applicant/about-to-start")
                .aboutToSubmitURL("/enter-applicant/about-to-submit")
                .midEventURL("/enter-applicant/mid-event")
                .field(CaseData::getApplicants, DisplayContext.Optional)
                .field(CaseData::getSolicitor, DisplayContext.Optional);

        builder.event("enterOthers").forState("Open")
                .name("Others to be given notice")
                .description("Entering others for the case")
                .field(CaseData::getOthers, DisplayContext.Optional);

        builder.event("enterGrounds").forState("Open")
                .name("Grounds for the application")
                .description("Entering the grounds for the application")
                .field().id("EPO_REASONING_SHOW").context(DisplayContext.Optional).showCondition("groundsForEPO CONTAINS \"Workaround to show groundsForEPO. Needs to be hidden from UI\"").done()
                .field().id(CaseData::getGroundsForEPO).context(DisplayContext.Optional).showCondition("EPO_REASONING_SHOW CONTAINS \"SHOW_FIELD\"").done()
                .field(CaseData::getGrounds, DisplayContext.Optional);

        builder.event("enterRiskHarm").forState("Open")
                .name("Risk and harm to children")
                .description("Entering opinion on risk and harm to children")
                .field(CaseData::getRisks, DisplayContext.Optional);

        builder.event("enterParentingFactors").forState("Open")
                .name("Factors affecting parenting")
                .description("Entering the factors affecting parenting")
                .grant("caseworker-publiclaw-solicitor", "CRU")
                .field(CaseData::getFactorsParenting, DisplayContext.Optional);

        builder.event("enterInternationalElement").forState("Open")
                .name("International element")
                .description("Entering the international element")
                .field(CaseData::getInternationalElement, DisplayContext.Optional);

        builder.event("otherProceedings").forState("Open")
                .name("Other proceedings")
                .description("Entering other proceedings and proposals")
                .midEventURL("/enter-other-proceedings/mid-event")
                .field(CaseData::getProceeding, DisplayContext.Optional);

        builder.event("otherProposal").forState("Open")
                .name("Allocation proposal")
                .description("Entering other proceedings and allocation proposals")
                .label("allocationProposal_label", "This should be completed by a solicitor with good knowledge of the case. Use the [President's Guidance](https://www.judiciary.uk/wp-content/uploads/2013/03/President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) and [schedule](https://www.judiciary.uk/wp-content/uploads/2013/03/Schedule-to-the-President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) on allocation and gatekeeping to make your recommendation.")
                .field(CaseData::getAllocationProposal, DisplayContext.Optional);

        builder.event("attendingHearing").forState("Open")
                .name("Attending the hearing")
                .description("Enter extra support needed for anyone to take part in hearing")
                .displayOrder(13)
                .field(CaseData::getHearingPreferences, DisplayContext.Optional);

        builder.event("changeCaseName").forState("Open")
                .name("Change case name")
                .description("Change case name")
                .field(CaseData::getCaseName, DisplayContext.Optional)
                .displayOrder(15);

        builder.event("addCaseIDReference").forState("Open")
                .name("Add case ID")
                .description("Add case ID")
                .pageLabel("Add Case ID")
                .field("caseIDReference", DisplayContext.Optional)
                .displayOrder(16);
    }

}
