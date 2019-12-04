package uk.gov.hmcts.reform.fpl;

import ccd.sdk.types.CCDConfig;
import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.FieldType;
import uk.gov.hmcts.reform.ccd.client.model.Event;

public class FPLConfig implements CCDConfig {

    @Override
    public void configure(ConfigBuilder builder) {

        builder.caseType("CARE_SUPERVISION_EPO");

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
                .description("Entering the factors affecting parenting");

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
                .description("Change case name");

        builder.event("addCaseIDReference").forState("Open")
                .name("Add case ID")
                .description("Add case ID");
    }

}
