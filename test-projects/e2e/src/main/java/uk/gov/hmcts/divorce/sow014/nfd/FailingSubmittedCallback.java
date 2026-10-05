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

    public static final String NO_RETRIES_EVENT_ID = "failing-submitted-no-retries";
    public static final String RECOVER_NOTE = "recover-submitted-callback";
    public static volatile int callbackAttempts = 0;

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        for (String eventId : new String[] {FailingSubmittedCallback.class.getSimpleName(), NO_RETRIES_EVENT_ID}) {
            var event = configBuilder.event(eventId)
                .forAllStates()
                .name("Failing submitted callback")
                .description("Exercise submitted callback failure and recovery")
                .submittedCallback(this::submitted)
                .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
                .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
                .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
            if (!NO_RETRIES_EVENT_ID.equals(eventId)) {
                event.retries(3);
            }
            new PageBuilder(event)
                .page("addCaseNotes")
                .optional(CaseData::getNote)
                .optional(CaseData::getSetInMidEvent);
        }
    }

    private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDetails, CaseDetails<CaseData, State> caseDetails1) {
        callbackAttempts++;
        if (RECOVER_NOTE.equals(caseDetails.getData().getNote()) && callbackAttempts == 2) {
            return SubmittedCallbackResponse.builder()
                .confirmationHeader(SubmittedConfirmationCallback.CONFIRMATION_HEADER)
                .confirmationBody(SubmittedConfirmationCallback.CONFIRMATION_BODY)
                .build();
        }
        throw new RuntimeException("Private callback diagnostic must not be returned to the caller");
    }
}
