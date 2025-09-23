package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
@Slf4j
public class FailingSubmittedCallback implements CCDConfig<CaseData, State, UserRole> {

    public static int callbackAttempts = 0;

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(FailingSubmittedCallback.class.getSimpleName())
            .forAllStates()
            .name("Fail")
            .description("Fail")
            .submittedCallback(this::submitted)
            .retries(3)
            .grant(CREATE_READ_UPDATE,
                CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE,
                SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("addCaseNotes")
            .pageLabel("Add case notes")
            .optional(CaseData::getNote);
    }

    private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDetails, CaseDetails<CaseData, State> caseDetails1) {
        callbackAttempts++;
        throw new RuntimeException();
    }
}
