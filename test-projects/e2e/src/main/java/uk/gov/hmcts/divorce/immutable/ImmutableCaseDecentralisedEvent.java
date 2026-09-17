package uk.gov.hmcts.divorce.immutable;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.ImmutableCaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class ImmutableCaseDecentralisedEvent implements CCDConfig<ImmutableCaseData, State, UserRole> {

    public static final String EVENT_ID = "immutable-decentralised";

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(ImmutableCaseConfiguration.CASE_TYPE);
    }

    @Override
    public void configureDecentralised(
        DecentralisedConfigBuilder<ImmutableCaseData, State, UserRole> configBuilder
    ) {
        configBuilder.decentralisedEvent(EVENT_ID, this::submit, EventPayload::caseData)
            .forAllStates()
            .name("Immutable decentralised")
            .description("Test immutable case data in decentralised submission")
            .grant(CREATE_READ_UPDATE_DELETE, CASE_WORKER, SUPER_USER);
    }

    private SubmitResponse<State> submit(EventPayload<ImmutableCaseData, State> payload) {
        ImmutableCaseData caseData = payload.caseData();
        return SubmitResponse.<State>builder()
            .confirmationHeader(caseData.getFirstValue() + ":" + caseData.getSecondValue())
            .build();
    }
}
