package uk.gov.hmcts.divorce.sow014.nfd;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.getenv;
import static uk.gov.hmcts.divorce.divorcecase.model.State.Draft;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CITIZEN;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Slf4j
@Component
public class ReturnErrorWhenCreateTestCase implements CCDConfig<CaseData, State, UserRole> {

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        var roles = new ArrayList<UserRole>();

        roles.add(SOLICITOR);
        roles.add(CASE_WORKER);

        new PageBuilder(configBuilder
            .event(ReturnErrorWhenCreateTestCase.class.getSimpleName())
            .initialState(Draft)
            .aboutToSubmitCallback(this::submit)
            .name("Returns error")
            .grant(CREATE_READ_UPDATE, roles.toArray(UserRole[]::new))
            .grantHistoryOnly(SUPER_USER, CASE_WORKER, LEGAL_ADVISOR, SOLICITOR, CITIZEN, JUDGE))
            .page("Create test case")
            .mandatory(CaseData::getApplicationType)
            .done();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> submit(CaseDetails<CaseData, State> caseDetails,
                                                                 CaseDetails<CaseData, State> caseDetails1) {
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(caseDetails.getData())
            .errors(List.of("This is a test error message."))
            .build();

    }

}
