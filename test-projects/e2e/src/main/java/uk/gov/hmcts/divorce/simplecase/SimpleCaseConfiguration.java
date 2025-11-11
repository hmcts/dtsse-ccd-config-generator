package uk.gov.hmcts.divorce.simplecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseData;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;

import java.util.EnumSet;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

@Component
@Slf4j
public class SimpleCaseConfiguration implements CCDConfig<SimpleCaseData, SimpleCaseState, UserRole> {

    public static final String CASE_TYPE = "E2E_SIMPLE";
    public static final String CASE_TYPE_DESCRIPTION = "Simple e2e case type";
    public static final String JURISDICTION = "DIVORCE";
    public static final String CREATE_EVENT = "create-simple-case";
    public static final String FOLLOW_UP_EVENT = "simple-case-follow-up";
    public static final String START_CALLBACK_MARKER = "simple-case-start";
    public static final String SUBMIT_CALLBACK_MARKER = "simple-case-creation";
    public static final String FOLLOW_UP_CALLBACK_MARKER = "simple-case-follow-up-callback";

    @Override
    public void configure(final ConfigBuilder<SimpleCaseData, SimpleCaseState, UserRole> configBuilder) {
        configBuilder.setCallbackHost("http://localhost:4013");
        configBuilder.caseType(CASE_TYPE, CASE_TYPE_DESCRIPTION, "Additional simple case type for e2e tests");
        configBuilder.jurisdiction(JURISDICTION, "Family Divorce", "Family Divorce: simple case tests");

        var createEventBuilder = configBuilder
            .event(CREATE_EVENT)
            .forStateTransition(EnumSet.noneOf(SimpleCaseState.class), SimpleCaseState.CREATED)
            .aboutToStartCallback(this::startCreation)
            .aboutToSubmitCallback(this::submitCreation)
            .name("Create simple case")
            .description("Create a simplified case for additional scenarios")
            .grant(CREATE_READ_UPDATE, UserRole.CASE_WORKER)
            .grantHistoryOnly(UserRole.SUPER_USER);

        createEventBuilder.fields()
            .page("simpleCaseCreation")
            .mandatory(SimpleCaseData::getSubject)
            .optional(SimpleCaseData::getDescription)
            .optional(SimpleCaseData::getCreationMarker)
            .done();

        var followUpEventBuilder = configBuilder
            .event(FOLLOW_UP_EVENT)
            .forStateTransition(SimpleCaseState.CREATED, SimpleCaseState.FOLLOW_UP)
            .aboutToSubmitCallback(this::submitFollowUp)
            .name("Simple case follow up")
            .description("Record follow-up details")
            .grant(CREATE_READ_UPDATE, UserRole.CASE_WORKER)
            .grantHistoryOnly(UserRole.SUPER_USER);

        followUpEventBuilder.fields()
            .page("simpleCaseFollowUp")
            .optional(SimpleCaseData::getFollowUpNote)
            .optional(SimpleCaseData::getFollowUpMarker)
            .done();

        configBuilder.searchInputFields()
            .field(SimpleCaseData::getSubject, "Subject");
        configBuilder.searchResultFields()
            .field(SimpleCaseData::getSubject, "Subject");
        configBuilder.workBasketInputFields()
            .field(SimpleCaseData::getSubject, "Subject");
        configBuilder.workBasketResultFields()
            .field(SimpleCaseData::getSubject, "Subject");
    }

    private AboutToStartOrSubmitResponse<SimpleCaseData, SimpleCaseState> startCreation(
        CaseDetails<SimpleCaseData, SimpleCaseState> details
    ) {
        log.info("Simple case start callback invoked for event {}", CREATE_EVENT);
        var caseData = details.getData();
        caseData.setCreationMarker(START_CALLBACK_MARKER);
        log.info("Simple case creation marker set during start: {}", caseData.getCreationMarker());
        return AboutToStartOrSubmitResponse.<SimpleCaseData, SimpleCaseState>builder()
            .data(caseData)
            .build();
    }

    private AboutToStartOrSubmitResponse<SimpleCaseData, SimpleCaseState> submitCreation(
        CaseDetails<SimpleCaseData, SimpleCaseState> details,
        CaseDetails<SimpleCaseData, SimpleCaseState> before
    ) {
        var caseData = details.getData();
        log.info(
            "Simple case submit callback invoked for event {} with subject {} and marker {}",
            CREATE_EVENT,
            caseData.getSubject(),
            caseData.getCreationMarker()
        );
        if (caseData.getDescription() == null) {
            caseData.setDescription("Created simple case for " + caseData.getSubject());
        }
        caseData.setCreationMarker(SUBMIT_CALLBACK_MARKER);
        log.info("Simple case creation marker set during submit: {}", caseData.getCreationMarker());
        return AboutToStartOrSubmitResponse.<SimpleCaseData, SimpleCaseState>builder()
            .data(caseData)
            .state(SimpleCaseState.CREATED)
            .build();
    }

    private AboutToStartOrSubmitResponse<SimpleCaseData, SimpleCaseState> submitFollowUp(
        CaseDetails<SimpleCaseData, SimpleCaseState> details,
        CaseDetails<SimpleCaseData, SimpleCaseState> before
    ) {
        var caseData = details.getData();
        log.info(
            "Simple case follow-up callback invoked for event {} with note {} and marker {}",
            FOLLOW_UP_EVENT,
            caseData.getFollowUpNote(),
            caseData.getFollowUpMarker()
        );
        String followUpNote = caseData.getFollowUpNote();
        if (followUpNote == null) {
            followUpNote = "";
        }
        caseData.setFollowUpNote(followUpNote + " (processed)");
        caseData.setFollowUpMarker(FOLLOW_UP_CALLBACK_MARKER);
        log.info("Simple case follow-up marker set during submit: {}", caseData.getFollowUpMarker());
        return AboutToStartOrSubmitResponse.<SimpleCaseData, SimpleCaseState>builder()
            .data(caseData)
            .state(SimpleCaseState.FOLLOW_UP)
            .build();
    }
}
