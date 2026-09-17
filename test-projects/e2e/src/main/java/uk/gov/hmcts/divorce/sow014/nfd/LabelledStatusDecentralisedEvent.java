package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class LabelledStatusDecentralisedEvent implements CCDConfig<CaseData, State, UserRole> {

    public static final String EVENT_ID = "labelled-status-dec";

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder.decentralisedEvent(EVENT_ID, this::submit, EventPayload::caseData)
            .forAllStates()
            .name("Labelled status")
            .description("Test enum name handling")
            .grant(CREATE_READ_UPDATE_DELETE, CASE_WORKER, SUPER_USER);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        return SubmitResponse.<State>builder()
            .confirmationBody(payload.caseData().getLabelledStatus().name())
            .build();
    }
}
