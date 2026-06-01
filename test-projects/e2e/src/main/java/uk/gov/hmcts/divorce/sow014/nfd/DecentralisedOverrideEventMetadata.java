package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class DecentralisedOverrideEventMetadata implements CCDConfig<CaseData, State, UserRole> {

    public static final String EVENT_ID = "cw-dec-override-event-metadata";
    public static final String METADATA_OVERRIDE_PREFIX = "submit handler override";

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        EventBuilder<CaseData, UserRole, State> eventBuilder = configBuilder
            .decentralisedEvent(EVENT_ID, this::submit, this::start)
            .forAllStates()
            .name("Override metadata dec")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);

        new PageBuilder(eventBuilder)
            .page("overrideEventMetadataDecentralised")
            .pageLabel("Override metadata dec")
            .optional(CaseData::getNote);
    }

    private CaseData start(EventPayload<CaseData, State> payload) {
        return payload.caseData();
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        return SubmitResponse.<State>builder()
            .eventMetadata(EventMetadata.builder()
                .summary(METADATA_OVERRIDE_PREFIX + " summary")
                .description(METADATA_OVERRIDE_PREFIX + " description")
                .build())
            .build();
    }
}
