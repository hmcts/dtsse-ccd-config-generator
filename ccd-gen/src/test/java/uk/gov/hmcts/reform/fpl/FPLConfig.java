package uk.gov.hmcts.reform.fpl;


import ccd.sdk.types.CCDConfig;
import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.DisplayContext;
import ccd.sdk.types.FieldCollection;
import de.cronn.reflection.util.TypedPropertyGetter;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.*;
import uk.gov.hmcts.reform.fpl.model.common.C2DocumentBundle;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

import static uk.gov.hmcts.reform.fpl.enums.UserRole.*;

// Found and invoked by the config generator.
// The CaseData type parameter tells the generator which class represents your case model.
public class FPLConfig implements CCDConfig<CaseData> {

    @Override
    public void configure(ConfigBuilder<CaseData> builder) {
        builder.caseType("CARE_SUPERVISION_EPO");

        builder.grant("Open", "CRU", LOCAL_AUTHORITY.getRole());
        builder.grant("Gatekeeping", "CRU", UserRole.GATEKEEPER.getRole());
        builder.grant("Submitted", "CRU", HMCTS_ADMIN.getRole());
        builder.grant("-PREPARE_FOR_HEARING", "CRU", HMCTS_ADMIN.getRole());
        builder.blacklist("-PREPARE_FOR_HEARING", GATEKEEPER.getRole(), LOCAL_AUTHORITY.getRole());
        builder.explicitState("hearingBookingDetails", JUDICIARY.getRole(), "CRU");

        buildInitialEvents(builder);
        buildSharedEvents(builder, "Submitted", "Gatekeeping", "-PREPARE_FOR_HEARING");
        buildPrepareForHearingEvents(builder);
        buildC21Events(builder);
        buildUniversalEvents(builder);
        buildSubmittedEvents(builder);
        buildGatekeepingEvents(builder);
        buildStandardDirections(builder, "Submitted", "");
        buildStandardDirections(builder, "Gatekeeping", "AfterGatekeeping");
        buildTransitions(builder);

        builder.event("internal-changeState:Gatekeeping->PREPARE_FOR_HEARING")
                .forStateTransition("Gatekeeping", "PREPARE_FOR_HEARING")
                .explicitGrants()
                .grant("C", SYSTEM_UPDATE.getRole());
    }



    private void buildC21Events(ConfigBuilder<CaseData> builder) {
        builder.event("createC21Order")
                .forStates("Submitted", "Gatekeeping")
                .explicitGrants()
                .grant("CRU", HMCTS_ADMIN.getRole(), UserRole.JUDICIARY.getRole())
                .name("Create an order")
                .allWebhooks("create-order")
                .midEventWebhook()
                .fields()
                    .page("OrderInformation")
                    .complex(CaseData::getC21Order)
                        .optional(C21Order::getOrderTitle)
                        .mandatory(C21Order::getOrderDetails)
                        .readonly(C21Order::getDocument, "document=\"DO_NOT_SHOW\"")
                        .readonly(C21Order::getOrderDate, "document=\"DO_NOT_SHOW\"")
                        .done()
                    .page("JudgeInformation")
                    .complex(CaseData::getJudgeAndLegalAdvisor)
                        .optional(JudgeAndLegalAdvisor::getJudgeTitle)
                        .mandatory(JudgeAndLegalAdvisor::getJudgeLastName)
                        .optional(JudgeAndLegalAdvisor::getJudgeFullName)
                        .optional(JudgeAndLegalAdvisor::getLegalAdvisorName);
    }

    private void buildTransitions(ConfigBuilder<CaseData> builder) {
        builder.event("submitApplication")
                .forStateTransition("Open", "Submitted")
                .grant("R", HMCTS_ADMIN.getRole(), UserRole.CAFCASS.getRole(), UserRole.JUDICIARY.getRole(), UserRole.GATEKEEPER.getRole())
                .grant("CRU", LOCAL_AUTHORITY.getRole())
                .name("Submit application")
                .endButtonLabel("Submit")
                .allWebhooks("case-submission")
                .midEventWebhook()
                .retries(1,2,3,4,5)
                .fields()
                    .label("submissionConsentLabel", "")
                    .field("submissionConsent", DisplayContext.Mandatory);

        builder.event("populateSDO")
                .forStateTransition("Submitted", "Gatekeeping")
                .name("Populate standard directions")
                .explicitGrants()
                .grant("C", UserRole.SYSTEM_UPDATE.getRole())
                .fields()
                    .pageLabel(" ")
                    .optional(CaseData::getAllParties)
                    .optional(CaseData::getLocalAuthorityDirections)
                    .optional(CaseData::getRespondentDirections)
                    .optional(CaseData::getCafcassDirections)
                    .optional(CaseData::getOtherPartiesDirections)
                    .optional(CaseData::getCourtDirections);

        builder.event("deleteApplication")
                .forStateTransition("Open", "Deleted")
                .grant("CRU", LOCAL_AUTHORITY.getRole())
                .name("Delete an application")
                .aboutToSubmitURL("/case-deletion/about-to-submit")
                .endButtonLabel("Delete application")
                .fields()
                    .field("deletionConsent", DisplayContext.Mandatory);
    }

    private void buildGatekeepingEvents(ConfigBuilder<CaseData> builder) {
        builder.event("otherAllocationDecision")
                .forState("Gatekeeping")
                .name("Allocation decision")
                .description("Entering other proceedings and allocation proposals")
                .aboutToStartURL("/allocation-decision/about-to-start")
                .aboutToSubmitURL("/allocation-decision/about-to-submit")
                .retries(1,2,3,4,5)
                .fields()
                    .field(CaseData::getAllocationDecision, DisplayContext.Mandatory, true);

        builder.event("draftSDO")
                .forState("Gatekeeping")
                .name("Draft standard directions")
                .allWebhooks("draft-standard-directions")
                .midEventWebhook()
                .fields()
                    .page("judgeAndLegalAdvisor")
                        .optional(CaseData::getJudgeAndLegalAdvisor)
                    .page("allPartiesDirections")
                        .readonly(CaseData::getHearingDetails, "hearingDate=\"DO_NOT_SHOW\"")
                        .field().id("hearingDate").context(DisplayContext.ReadOnly).showCondition("hearingDetails.startDate=\"\"").context(DisplayContext.ReadOnly).done()
                        .complex(CaseData::getAllParties, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getAllPartiesCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("localAuthorityDirections")
                        .complex(CaseData::getLocalAuthorityDirections, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getLocalAuthorityDirectionsCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("parentsAndRespondentsDirections")
                        .complex(CaseData::getRespondentDirections, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getRespondentDirectionsCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("cafcassDirections")
                        .complex(CaseData::getCafcassDirections, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getCafcassDirectionsCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("otherPartiesDirections")
                        .complex(CaseData::getOtherPartiesDirections, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getOtherPartiesDirectionsCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("courtDirections")
                        .complex(CaseData::getCourtDirections, Direction.class, this::renderSDODirection)
                        .complex(CaseData::getCourtDirectionsCustom, Direction.class, this::renderSDODirectionsCustom)
                    .page("documentReview")
                        .complex(CaseData::getStandardDirectionOrder)
                            .readonly(Order::getOrderDoc)
                            .mandatory(Order::getOrderStatus);
        builder.event("uploadDocumentsAfterGatekeeping")
                .forState("Gatekeeping")
                .explicitGrants()
                .grant("R", LOCAL_AUTHORITY.getRole());
    }

    private void renderSDODirectionsCustom(FieldCollection.FieldCollectionBuilder<Direction,?> builder) {
        builder.optional(Direction::getDirectionType)
                .optional(Direction::getDirectionText)
                .optional(Direction::getDateToBeCompletedBy);
    }

    private void renderSDODirection(FieldCollection.FieldCollectionBuilder<Direction,?> builder) {
        builder.readonly(Direction::getReadOnly)
                .readonly(Direction::getDirectionRemovable)
                .readonly(Direction::getDirectionType)
                .optional(Direction::getDirectionNeeded)
                .optional(Direction::getDirectionText, "{{FIELD_NAME}}.readOnly!=\"Yes\" AND {{FIELD_NAME}}.directionNeeded!=\"No\"")
                .optional(Direction::getDateToBeCompletedBy);
    }

    private void buildStandardDirections(ConfigBuilder<CaseData> builder, String state, String suffix) {
        builder.event("uploadStandardDirections" + suffix)
                .forState(state)
                .name("Documents")
                .description("Upload standard directions")
                .explicitGrants()
                .grant("CRU", HMCTS_ADMIN.getRole())
                .fields()
                    .label("standardDirectionsLabel", "Upload standard directions and other relevant documents, for example the C6 Notice of Proceedings or C9 statement of service.")
                    .label("standardDirectionsTitle", "## 1. Standard directions")
                    .field("standardDirectionsDocument", DisplayContext.Optional)
                    .field("otherCourtAdminDocuments", DisplayContext.Optional);
    }

    private void buildSubmittedEvents(ConfigBuilder<CaseData> builder) {
        builder.event("addFamilyManCaseNumber")
                .forState("Submitted")
                .name("Add case number")
                .fields()
                    .optional(CaseData::getFamilyManCaseNumber);

        builder.event("addStatementOfService")
                .forState("Submitted")
                .explicitGrants()
                .grant("CRU", LOCAL_AUTHORITY.getRole())
                .name("Add statement of service (c9)")
                .description("Add statement of service")
                .aboutToStartURL("/statement-of-service/about-to-start")
                .fields()
                    .label("c9Declaration", "If you send documents to a party's solicitor or a children's guardian, give their details") .field(CaseData::getStatementOfService, DisplayContext.Mandatory, true) .label("serviceDeclarationLabel", "Declaration" ) .field("serviceConsent", DisplayContext.Mandatory);


        builder.event("uploadDocumentsAfterSubmission")
                .forState("Submitted")
                .explicitGrants()
                .grant("R", LOCAL_AUTHORITY.getRole())
                .name("Documents")
                .description("Only here for backwards compatibility with case history");

        builder.event("sendToGatekeeper")
                .forState("Submitted")
                .name("Send to gatekeeper")
                .description("Send email to gatekeeper")
                .explicitGrants()
                .grant("CRU", HMCTS_ADMIN.getRole())
                .fields()
                .label("gateKeeperLabel", "Let the gatekeeper know there's a new case")
                .mandatory(CaseData::getGatekeeperEmail);
    }

    private void buildUniversalEvents(ConfigBuilder<CaseData> builder) {
        builder.event("uploadDocuments")
                .forAllStates()
                .explicitGrants()
                .grant("CRU", LOCAL_AUTHORITY.getRole())
                .name("Documents")
                .description("Upload documents")
                .midEventWebhook()
                .fields()
                    .label("uploadDocuments_paragraph_1", "You must upload these documents if possible. Give the reason and date you expect to provide it if you don’t have a document yet.")
                    .optional(CaseData::getSocialWorkChronologyDocument)
                    .optional(CaseData::getSocialWorkStatementDocument)
                    .optional(CaseData::getSocialWorkAssessmentDocument)
                    .optional(CaseData::getSocialWorkCarePlanDocument)
                    .optional(CaseData::getSocialWorkEvidenceTemplateDocument)
                    .optional(CaseData::getThresholdDocument)
                    .optional(CaseData::getChecklistDocument)
                    .field("[STATE]", DisplayContext.ReadOnly, "courtBundle = \"DO_NOT_SHOW\"")
                    .field("courtBundle", DisplayContext.Optional, "[STATE] != \"Open\"")
                    .label("documents_socialWorkOther_border_top", "---------------------------------")
                    .optional(CaseData::getOtherSocialWorkDocuments)
                    .label("documents_socialWorkOther_border_bottom", "---------------------------------");
    }

    private void buildPrepareForHearingEvents(ConfigBuilder<CaseData> builder) {
        builder.event("uploadOtherCourtAdminDocuments-PREPARE_FOR_HEARING")
                .forState("PREPARE_FOR_HEARING")
                .grant("CRU", HMCTS_ADMIN.getRole())
                .fields()
                    .field("otherCourtAdminDocuments", DisplayContext.Optional);

        builder.event("draftCMO")
                .forState("PREPARE_FOR_HEARING")
                .grant("CRU", LOCAL_AUTHORITY.getRole())
                .name("Draft CMO")
                .description("Draft Case Management Order")
                .fields()
                .page("hearingDate")
                    .field("cmoHearingDateList", DisplayContext.Mandatory)
                .page("allPartiesDirections")
                    .label("allPartiesLabelCMO", "## For all parties")
                    .label("allPartiesPrecedentLabelCMO", "Add completed directions from the precedent library or your own template.")
                    .complex(CaseData::getAllPartiesCustom, Direction.class, this::renderDirection)
                .page("localAuthorityDirections")
                     .label("localAuthorityDirectionsLabelCMO", "## For the local authority")
                     .complex(CaseData::getLocalAuthorityDirectionsCustom, Direction.class, this::renderDirection)
                .page(2)
                     .label("respondentsDirectionLabelCMO", "For the parents or respondents")
                     .label("respondentsDropdownLabelCMO", " ")
                     .complex(CaseData::getRespondentDirectionsCustom, Direction.class)
                        .optional(Direction::getDirectionType)
                        .mandatory(Direction::getDirectionText)
                        .mandatory(Direction::getParentsAndRespondentsAssignee)
                        .optional(Direction::getDateToBeCompletedBy)
                    .done()
                .page("cafcassDirections")
                     .label("cafcassDirectionsLabelCMO", "## For Cafcass")
                     .complex(CaseData::getCafcassDirectionsCustom, Direction.class, this::renderDirection)
                    .complex(CaseData::getCaseManagementOrder)
                        .readonly(CaseManagementOrder::getHearingDate)
                    .done()
                .page(3)
                     .label("otherPartiesDirectionLabelCMO", "## For other parties")
                     .label("otherPartiesDropdownLabelCMO", " ")
                     .complex(CaseData::getOtherPartiesDirectionsCustom, Direction.class)
                        .optional(Direction::getDirectionType)
                        .mandatory(Direction::getDirectionText)
                        .mandatory(Direction::getOtherPartiesAssignee)
                        .optional(Direction::getDateToBeCompletedBy)
                    .done()
                .page("courtDirections")
                     .label("courtDirectionsLabelCMO", "## For the court")
                     .complex(CaseData::getCourtDirectionsCustom, Direction.class, this::renderDirection)
                .page(5)
                     .label("orderBasisLabel", "## Basis of order")
                     .label("addRecitalLabel", "## Add recital")
                     .field("recitals", DisplayContext.Optional)
                .page("schedule")
                     .field("schedule", DisplayContext.Mandatory);

        renderComply(builder, "COMPLY_LOCAL_AUTHORITY", LOCAL_AUTHORITY, CaseData::getLocalAuthorityDirections, DisplayContext.Mandatory, "Allows Local Authority user access to comply with their directions as well as ones for all parties");
        renderComply(builder, "COMPLY_CAFCASS", UserRole.CAFCASS, CaseData::getCafcassDirections, DisplayContext.Optional, "Allows Local Authority user access to comply with their directions as well as ones for all parties");
        renderComply(builder, "COMPLY_COURT", HMCTS_ADMIN, CaseData::getCourtDirectionsCustom, DisplayContext.Optional, "Allows Local Authority user access to comply with their directions as well as ones for all parties");
    }

    private void renderComply(ConfigBuilder<CaseData> builder, String eventId, UserRole role, TypedPropertyGetter<CaseData, ?> getter, DisplayContext reasonContext, String description) {
        builder.event(eventId)
                .forState("PREPARE_FOR_HEARING")
                .grant("CRU", role.getRole())
                .name("Comply with directions")
                .description(description)
                .fields()
                .pageLabel(" ")
                .complex(getter, Direction.class)
                    .readonly(Direction::getDirectionType)
                    .readonly(Direction::getDirectionNeeded, "directionText = \"DO_NOT_SHOW\"")
                    .readonly(Direction::getDateToBeCompletedBy)
                    .complex(Direction::getResponse)
                        .optional(DirectionResponse::getComplied)
                        .optional(DirectionResponse::getDocumentDetails)
                        .optional(DirectionResponse::getFile)
                        .label("cannotComplyTitle", "TODO")
                        .field(DirectionResponse::getCannotComplyReason, reasonContext)
                        .optional(DirectionResponse::getC2Uploaded)
                        .optional(DirectionResponse::getCannotComplyFile);
    }

    private void renderDirection(FieldCollection.FieldCollectionBuilder<Direction, ?> builder) {
        builder.optional(Direction::getDirectionType)
                .mandatory(Direction::getDirectionText)
                .optional(Direction::getDateToBeCompletedBy);
    }

    private void buildSharedEvents(ConfigBuilder<CaseData> builder, String... states) {
        builder.event("hearingBookingDetails")
                .forStates(states)
                .grant("CRU", UserRole.GATEKEEPER.getRole())
                .name("Add hearing details")
                .description("Add hearing booking details to a case")
                .midEventURL("/add-hearing-bookings/mid-event")
                .fields()
                .complex(CaseData::getHearingDetails, HearingBooking.class)
                    .mandatory(HearingBooking::getType)
                    .mandatory(HearingBooking::getTypeDetails, "hearingDetails.type=\"OTHER\"")
                    .mandatory(HearingBooking::getVenue)
                    .mandatory(HearingBooking::getStartDate)
                    .mandatory(HearingBooking::getEndDate)
                    .mandatory(HearingBooking::getHearingNeedsBooked)
                    .mandatory(HearingBooking::getHearingNeedsDetails, "hearingDetails.hearingNeedsBooked!=\"NONE\"")
                    .complex(HearingBooking::getJudgeAndLegalAdvisor)
                        .mandatory(JudgeAndLegalAdvisor::getJudgeTitle)
                        .mandatory(JudgeAndLegalAdvisor::getOtherTitle, "hearingDetails.judgeAndLegalAdvisor.judgeTitle=\"OTHER\"")
                        .mandatory(JudgeAndLegalAdvisor::getJudgeLastName, "hearingDetails.judgeAndLegalAdvisor.judgeTitle!=\"MAGISTRATES\" AND hearingDetails.judgeAndLegalAdvisor.judgeTitle!=\"\"")
                        .optional(JudgeAndLegalAdvisor::getJudgeFullName, "hearingDetails.judgeAndLegalAdvisor.judgeTitle=\"MAGISTRATES\"")
                        .optional(JudgeAndLegalAdvisor::getLegalAdvisorName);

        builder.event("uploadC2")
                .forStates(states)
                .explicitGrants()
                .grant("CRU", UserRole.LOCAL_AUTHORITY.getRole(), UserRole.CAFCASS.getRole(), HMCTS_ADMIN.getRole())
                .name("Upload a C2")
                .description("Upload a c2 to the case")
                .midEventWebhook()
                .fields()
                    .complex(CaseData::getTemporaryC2Document)
                        .mandatory(C2DocumentBundle::getDocument)
                        .mandatory(C2DocumentBundle::getDescription);

        builder.event("amendChildren")
                .forStates(states)
                .name("Children")
                .description("Amending the children for the case")
                .fields()
                    .optional(CaseData::getChildren1);
        builder.event("amendRespondents")
                .forStates(states)
                .name("Respondents")
                .description("Amending the respondents for the case")
                .fields()
                    .optional(CaseData::getRespondents1);
        builder.event("amendOthers")
                .forStates(states)
                .name("Others to be given notice")
                .description("Amending others for the case")
                .fields()
                    .optional(CaseData::getOthers);
        builder.event("amendInternationalElement")
                .forStates(states)
                .name("International element")
                .description("Amending the international element")
                .fields()
                    .optional(CaseData::getInternationalElement);
        builder.event("amendOtherProceedings")
                .forStates(states)
                .name("Other proceedings")
                .description("Amending other proceedings and allocation proposals")
                .midEventURL("/enter-other-proceedings/mid-event")
                .fields()
                    .optional(CaseData::getProceeding);
        builder.event("amendAttendingHearing")
                .forStates(states)
                .name("Attending the hearing")
                .description("Amend extra support needed for anyone to take part in hearing")
                .fields()
                    .optional(CaseData::getHearingPreferences);
        builder.event("createNoticeOfProceedings")
                .forStates(states)
                .name("Create notice of proceedings")
                .grant("CRU", HMCTS_ADMIN.getRole())
                .fields()
                    .label("proceedingLabel", "## Other proceedings 1")
                    .complex(CaseData::getNoticeOfProceedings)
                        .complex(NoticeOfProceedings::getJudgeAndLegalAdvisor)
                            .optional(JudgeAndLegalAdvisor::getJudgeTitle)
                            .mandatory(JudgeAndLegalAdvisor::getJudgeLastName)
                            .optional(JudgeAndLegalAdvisor::getJudgeFullName)
                            .optional(JudgeAndLegalAdvisor::getLegalAdvisorName)
                        .done()
                        .mandatory(NoticeOfProceedings::getProceedingTypes);
    }

    private void buildInitialEvents(ConfigBuilder<CaseData> builder) {
        builder.event("openCase")
                .initialState("Open")
                .name("Start application")
                .description("Create a new case – add a title")
                .aboutToSubmitURL("/case-initiation/about-to-submit")
                .submittedURL("/case-initiation/submitted")
                .retries(1,2,3,4,5)
                .fields()
                    .optional(CaseData::getCaseName);

        builder.event("ordersNeeded").forState("Open")
                .name("Orders and directions needed")
                .description("Selecting the orders needed for application")
                .aboutToSubmitURL("/orders-needed/about-to-submit")
                .fields()
                    .optional(CaseData::getOrders);

        builder.event("hearingNeeded").forState("Open")
                .name("Hearing needed")
                .description("Selecting the hearing needed for application")
                .fields()
                    .optional(CaseData::getHearing);

        builder.event("enterChildren").forState("Open")
                .name("Children")
                .description("Entering the children for the case")
                .aboutToStartURL("/enter-children/about-to-start")
                .aboutToSubmitURL("/enter-children/about-to-submit")
                .fields()
                    .optional(CaseData::getChildren1);

        builder.event("enterRespondents").forState("Open")
                .name("Respondents")
                .description("Entering the respondents for the case")
                .aboutToStartWebhook()
                .aboutToSubmitWebhook()
                .midEventWebhook()
                .fields()
                    .optional(CaseData::getRespondents1);

        builder.event("enterApplicant").forState("Open")
                .name("Applicant")
                .description("Entering the applicant for the case")
                .aboutToStartWebhook()
                .aboutToSubmitWebhook()
                .midEventWebhook()
                .fields()
                    .optional(CaseData::getApplicants)
                    .optional(CaseData::getSolicitor);

        builder.event("enterOthers").forState("Open")
                .name("Others to be given notice")
                .description("Entering others for the case")
                .fields()
                    .optional(CaseData::getOthers);

        builder.event("enterGrounds").forState("Open")
                .name("Grounds for the application")
                .description("Entering the grounds for the application")
                .fields()
                    .field().id("EPO_REASONING_SHOW").context(DisplayContext.Optional).showCondition("groundsForEPO CONTAINS \"Workaround to show groundsForEPO. Needs to be hidden from UI\"").done()
                    .optional(CaseData::getGroundsForEPO, "EPO_REASONING_SHOW CONTAINS \"SHOW_FIELD\"")
                    .optional(CaseData::getGrounds);

        builder.event("enterRiskHarm").forState("Open")
                .name("Risk and harm to children")
                .description("Entering opinion on risk and harm to children")
                .fields()
                    .optional(CaseData::getRisks);

        builder.event("enterParentingFactors").forState("Open")
                .name("Factors affecting parenting")
                .description("Entering the factors affecting parenting")
                .grant("CRU", "caseworker-publiclaw-solicitor")
                .fields()
                    .optional(CaseData::getFactorsParenting);

        builder.event("enterInternationalElement").forState("Open")
                .name("International element")
                .description("Entering the international element")
                .fields()
                    .optional(CaseData::getInternationalElement);

        builder.event("otherProceedings").forState("Open")
                .name("Other proceedings")
                .description("Entering other proceedings and proposals")
                .midEventURL("/enter-other-proceedings/mid-event")
                .fields()
                    .optional(CaseData::getProceeding);

        builder.event("otherProposal").forState("Open")
                .name("Allocation proposal")
                .grant("CRU", GATEKEEPER.getRole())
                .description("Entering other proceedings and allocation proposals")
                .fields()
                    .label("allocationProposal_label", "This should be completed by a solicitor with good knowledge of the case. Use the [President's Guidance](https://www.judiciary.uk/wp-content/uploads/2013/03/President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) and [schedule](https://www.judiciary.uk/wp-content/uploads/2013/03/Schedule-to-the-President%E2%80%99s-Guidance-on-Allocation-and-Gatekeeping.pdf) on allocation and gatekeeping to make your recommendation.")
                    .optional(CaseData::getAllocationProposal);

        builder.event("attendingHearing").forState("Open")
                .name("Attending the hearing")
                .description("Enter extra support needed for anyone to take part in hearing")
                .displayOrder(13)
                .fields()
                    .optional(CaseData::getHearingPreferences);

        builder.event("changeCaseName").forState("Open")
                .name("Change case name")
                .description("Change case name")
                .displayOrder(15)
                .fields()
                    .optional(CaseData::getCaseName);

        builder.event("addCaseIDReference").forState("Open")
                .name("Add case ID")
                .description("Add case ID")
                .explicitGrants() // Do not inherit State level role permissions
                .displayOrder(16)
                .fields()
                    .pageLabel("Add Case ID")
                    .field("caseIDReference", DisplayContext.Optional);
    }
}
