package uk.gov.hmcts.divorce.simplecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.SortOrder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.JudicialUser;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseData;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseNote;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCasePriority;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;

import java.util.EnumSet;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.CONFIRM_DISPOSAL_EVENT_ID;
import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_EVENT_ID;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SYSTEMUPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

@Component
@Slf4j
public class SimpleCaseConfiguration implements CCDConfig<SimpleCaseData, SimpleCaseState, UserRole> {

    public static final String CASE_TYPE = "E2E_SIMPLE";
    public static final String CASE_TYPE_DESCRIPTION = "Simple e2e case type";
    public static final String JURISDICTION = "DIVORCE";
    public static final String CREATE_EVENT = "create-simple-case";
    public static final String FOLLOW_UP_EVENT = "simple-case-follow-up";
    public static final String STATE_ONLY_EVENT = "simple-case-state-only";
    public static final String EMPTY_DATA_EVENT = "simple-case-empty-data";
    public static final String OPTIONS_CREATE_EVENT = "create-simple-case-with-options";
    public static final String HISTORY_TAB = "simpleCaseHistory";
    public static final String PRINTABLE_DOCUMENTS_URL = "http://localhost:4013/simple-case/printable-documents";
    public static final String BANNER_DESCRIPTION = "Simple e2e case type service notice";
    public static final String BANNER_URL = "https://www.gov.uk/government/organisations/hm-courts-and-tribunals-service";
    public static final String BANNER_URL_TEXT = "HMCTS";
    public static final String OPTIONS_SUBJECT_LABEL = "Subject of the case";
    public static final String OPTIONS_SUBJECT_HINT = "A short summary of the case";
    public static final String OPTIONS_SUBJECT_PUBLISH_AS = "SimpleCaseOptionsSubject";
    public static final String OPTIONS_DESCRIPTION_SHOW_CONDITION = "subject=\"*\"";
    public static final String REVIEW_DATE_FORMAT = "#DATETIMEENTRY(dd-MM-yyyy)";
    public static final String DEFAULT_JUDGE_PERSONAL_CODE = "P000001";
    public static final String START_CALLBACK_MARKER = "simple-case-start";
    public static final String SUBMIT_CALLBACK_MARKER = "simple-case-creation";
    public static final String FOLLOW_UP_CALLBACK_MARKER = "simple-case-follow-up-callback";

    @Override
    public void configure(final ConfigBuilder<SimpleCaseData, SimpleCaseState, UserRole> configBuilder) {
        configBuilder.setCallbackHost("http://localhost:4013");
        configBuilder.caseType(CASE_TYPE, CASE_TYPE_DESCRIPTION, "Additional simple case type for e2e tests");
        configBuilder.jurisdiction(JURISDICTION, "Family Divorce", "Family Divorce: simple case tests");
        // DIVORCE is shared with E2E; this case type is imported after it, so its jurisdiction-level
        // Shuttered flag and Banner are the ones the definition store keeps.
        configBuilder.jurisdictionShuttered();
        configBuilder.banner(true, BANNER_DESCRIPTION, BANNER_URL, BANNER_URL_TEXT);
        configBuilder.enableForDeletion();
        configBuilder.printableDocumentsUrl(PRINTABLE_DOCUMENTS_URL);
        configBuilder.emitCaseRoleJurisdiction();
        configBuilder.noCaseHistoryTab();
        configBuilder.tab(HISTORY_TAB, "History")
            .field("caseHistory");

        configBuilder.explicitStateGrants();
        configBuilder.grant(SimpleCaseState.CREATED, CREATE_READ_UPDATE, UserRole.CASE_WORKER);
        configBuilder.grant(SimpleCaseState.FOLLOW_UP, CREATE_READ_UPDATE, UserRole.CASE_WORKER);
        configBuilder.grant(SimpleCaseState.FOLLOW_UP, CREATE_READ_UPDATE, SYSTEMUPDATE);
        configBuilder.grant(SimpleCaseState.PendingDisposal, CREATE_READ_UPDATE, SYSTEMUPDATE);

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

        configureOptionsCreateEvent(configBuilder);

        configBuilder
            .event(STATE_ONLY_EVENT)
            .forState(SimpleCaseState.FOLLOW_UP)
            .aboutToSubmitCallback(this::submitStateOnly)
            .name("Simple case state only")
            .description("About to submit returns a state and no data")
            .grant(CREATE_READ_UPDATE, UserRole.CASE_WORKER)
            .grantHistoryOnly(UserRole.SUPER_USER);

        configBuilder
            .event(EMPTY_DATA_EVENT)
            .forState(SimpleCaseState.FOLLOW_UP)
            .aboutToSubmitCallback(this::submitEmptyData)
            .name("Simple case empty data")
            .description("About to submit returns empty data")
            .grant(CREATE_READ_UPDATE, UserRole.CASE_WORKER)
            .grantHistoryOnly(UserRole.SUPER_USER);

        configBuilder
            .event(DISPOSAL_EVENT_ID)
            .forStateTransition(SimpleCaseState.FOLLOW_UP, SimpleCaseState.PendingDisposal)
            .name("Mark simple case for disposal")
            .description("Move a simple case into its disposal state")
            .grant(CREATE_READ_UPDATE, SYSTEMUPDATE);

        configBuilder
            .event(CONFIRM_DISPOSAL_EVENT_ID)
            .forStateTransition(SimpleCaseState.PendingDisposal, SimpleCaseState.PendingDisposal)
            .ttlIncrement(0)
            .name("Confirm simple case disposal")
            .description("Set the disposal TTL after verifying the case is readable")
            .grant(CREATE_READ_UPDATE, SYSTEMUPDATE);

        configBuilder.searchInputFields()
            .field(SimpleCaseData::getSubject, "Subject")
            .field(SimpleCaseData::getAllocatedJudge, "Allocated judge personal code",
                f -> f.listElementCode("personalCode").showCondition(OPTIONS_DESCRIPTION_SHOW_CONDITION));
        configBuilder.searchResultFields()
            .field(SimpleCaseData::getSubject, "Subject", f -> f.resultsOrdering(SortOrder.FIRST.ASCENDING));
        configBuilder.workBasketInputFields()
            .field(SimpleCaseData::getSubject, "Subject")
            .field(SimpleCaseData::getPriority, "Priority", UserRole.CASE_WORKER);
        configBuilder.workBasketResultFields()
            .field(SimpleCaseData::getSubject, "Subject")
            .field(SimpleCaseData::getAllocatedJudge, "Allocated judge personal code",
                f -> f.listElementCode("personalCode").role(UserRole.CASE_WORKER)
                    .resultsOrdering(SortOrder.FIRST.DESCENDING));
        configBuilder.searchCasesFields()
            .field(SimpleCaseData::getSubject, "Subject", f -> f.resultsOrdering("1:ASC"))
            .field(SimpleCaseData::getAllocatedJudge, "Allocated judge personal code",
                f -> f.listElementCode("personalCode").role(UserRole.CASE_WORKER).useCase("WORKBASKET"));
    }

    private void configureOptionsCreateEvent(
        final ConfigBuilder<SimpleCaseData, SimpleCaseState, UserRole> configBuilder
    ) {
        configBuilder
            .event(OPTIONS_CREATE_EVENT)
            .forStateTransition(EnumSet.noneOf(SimpleCaseState.class), SimpleCaseState.CREATED)
            .name("Create case with options")
            .description("Create a simple case through an event using per-field options")
            .canSaveDraft()
            .significant()
            .publishToCamunda()
            .grant(CREATE_READ_UPDATE, UserRole.CASE_WORKER)
            .grantHistoryOnly(UserRole.SUPER_USER)
            .fields()
            .page("simpleCaseOptions")
            .mandatory(SimpleCaseData::getSubject)
                .eventLabel(OPTIONS_SUBJECT_LABEL)
                .eventHint(OPTIONS_SUBJECT_HINT)
                .publishAs(OPTIONS_SUBJECT_PUBLISH_AS)
            .optional(SimpleCaseData::getDescription)
                .fieldShowCondition(OPTIONS_DESCRIPTION_SHOW_CONDITION)
                .retainHiddenValue()
                .showSummaryContentOption(1)
            .optional(SimpleCaseData::getPriority)
                .defaultValue(SimpleCasePriority.URGENT.name())
            .optional(SimpleCaseData::getReviewDate)
                .displayContextParameter(REVIEW_DATE_FORMAT)
            .optional(SimpleCaseData::getFollowUpNote)
                .nullifyByDefault()
                .publish(false)
            .optional(SimpleCaseData::getNotes)
            .complex(SimpleCaseData::getNotes, SimpleCaseNote.class)
                .optional(SimpleCaseNote::getNote)
                    .eventLabel("Options note")
                    .eventHint("Recorded against the new case")
                    .hintText("Free text note")
                    .pageId("simpleCaseOptions")
                .done()
            .complex(SimpleCaseData::getAllocatedJudge)
                .optional(JudicialUser::getPersonalCode)
                    .defaultValue(DEFAULT_JUDGE_PERSONAL_CODE)
                .done()
            .done();
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

    private AboutToStartOrSubmitResponse<SimpleCaseData, SimpleCaseState> submitStateOnly(
        CaseDetails<SimpleCaseData, SimpleCaseState> details,
        CaseDetails<SimpleCaseData, SimpleCaseState> before
    ) {
        return AboutToStartOrSubmitResponse.<SimpleCaseData, SimpleCaseState>builder()
            .state(SimpleCaseState.FOLLOW_UP)
            .build();
    }

    private AboutToStartOrSubmitResponse<SimpleCaseData, SimpleCaseState> submitEmptyData(
        CaseDetails<SimpleCaseData, SimpleCaseState> details,
        CaseDetails<SimpleCaseData, SimpleCaseState> before
    ) {
        return AboutToStartOrSubmitResponse.<SimpleCaseData, SimpleCaseState>builder()
            .data(new SimpleCaseData())
            .build();
    }

}
