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
    }

    private void buildSharedEvents(ConfigBuilder<CaseData> builder, String state, String suffix) {
        builder.event("hearingBookingDetails" + suffix)
                .name("Add hearing details")
                .description("Add hearing booking details to a case")
                .field(CaseData::getHearingDetails, DisplayContext.Optional)
                .forState(state);
        builder.event("uploadStandardDirections" + suffix)
                .name("Documents")
                .description("Upload standard directions")
                // TODO
                .forState(state);
        builder.event("uploadC2" + suffix)
                .name("Upload a C2")
                .description("Upload a c2 to the case")
                .field(CaseData::getTemporaryC2Document)
                .forState(state);
        builder.event("sendToGatekeeper" + suffix)
                .name("Send to gatekeeper")
                .description("Send email to gatekeeper")
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
                .field(CaseData::getNoticeOfProceedings)
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
                .retries("1,2,3,4,5");

        builder.event("ordersNeeded").forState("Open")
                .name("Orders and directions needed")
                .description("Selecting the orders needed for application")
                .aboutToSubmitURL("/orders-needed/about-to-submit");

        builder.event("hearingNeeded").forState("Open")
                .name("Hearing needed")
                .description("Selecting the hearing needed for application");

        builder.event("enterChildren").forState("Open")
                .name("Children")
                .description("Entering the children for the case")
                .aboutToStartURL("/enter-children/about-to-start")
                .aboutToSubmitURL("/enter-children/about-to-submit");

        builder.event("enterRespondents").forState("Open")
                .name("Respondents")
                .description("Entering the respondents for the case")
                .aboutToStartURL("/enter-respondents/about-to-start")
                .aboutToSubmitURL("/enter-respondents/about-to-submit");

        builder.event("enterApplicant").forState("Open")
                .name("Applicant")
                .description("Entering the applicant for the case")
                .aboutToStartURL("/enter-applicant/about-to-start")
                .aboutToSubmitURL("/enter-applicant/about-to-submit");

        builder.event("enterOthers").forState("Open")
                .name("Others to be given notice")
                .description("Entering others for the case");

        builder.event("enterGrounds").forState("Open")
                .name("Grounds for the application")
                .description("Entering the grounds for the application");

        builder.event("enterRiskHarm").forState("Open")
                .name("Risk and harm to children")
                .description("Entering opinion on risk and harm to children");

        builder.event("enterParentingFactors").forState("Open")
                .name("Factors affecting parenting")
                .description("Entering the factors affecting parenting")
                .grant("caseworker-publiclaw-solicitor", "CRU")
                .field(CaseData::getFactorsParenting, DisplayContext.Optional);

        builder.event("enterInternationalElement").forState("Open")
                .name("International element")
                .description("Entering the international element");

        builder.event("otherProceedings").forState("Open")
                .name("Other proceedings")
                .description("Entering other proceedings and proposals");

        builder.event("otherProposal").forState("Open")
                .name("Allocation proposal")
                .description("Entering other proceedings and allocation proposals");

        builder.event("attendingHearing").forState("Open")
                .name("Attending the hearing")
                .description("Enter extra support needed for anyone to take part in hearing")
                .displayOrder(13);

        builder.event("uploadDocuments").forState("*")
                .name("Documents")
                .description("Upload documents");

        builder.event("changeCaseName").forState("Open")
                .name("Change case name")
                .description("Change case name")
                .displayOrder(15);

        builder.event("addCaseIDReference").forState("Open")
                .name("Add case ID")
                .description("Add case ID")
                .displayOrder(16);
    }

}
