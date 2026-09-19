package uk.gov.hmcts.divorce.immutable;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.ImmutableCaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class ImmutableCaseConfiguration implements CCDConfig<ImmutableCaseData, State, UserRole> {

    public static final String CASE_TYPE = "E2E_IMMUTABLE";
    public static final String LEGACY_EVENT = "immutable-legacy";

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(CASE_TYPE);
    }

    @Override
    public void configure(ConfigBuilder<ImmutableCaseData, State, UserRole> configBuilder) {
        configBuilder.caseType(CASE_TYPE, "Immutable e2e case", "Immutable case data mapper tests");
        configBuilder.jurisdiction("DIVORCE", "Family Divorce", "Family Divorce");

        configBuilder.event(LEGACY_EVENT)
            .forAllStates()
            .aboutToSubmitCallback(this::aboutToSubmit)
            .name("Immutable legacy")
            .description("Test immutable case data in legacy submission")
            .grant(CREATE_READ_UPDATE_DELETE, CASE_WORKER, SUPER_USER);
    }

    private AboutToStartOrSubmitResponse<ImmutableCaseData, State> aboutToSubmit(
        CaseDetails<ImmutableCaseData, State> details,
        CaseDetails<ImmutableCaseData, State> before
    ) {
        return AboutToStartOrSubmitResponse.<ImmutableCaseData, State>builder()
            .data(details.getData())
            .build();
    }
}
