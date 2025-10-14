package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class CaseworkerUploadTestDocument implements CCDConfig<CaseData, State, UserRole> {
    public static final String DOCUMENT_UPDATED = "DocumentUpdated";

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        EventBuilder<CaseData, UserRole, State> eventBuilder = configBuilder
            .decentralisedEvent(DOCUMENT_UPDATED, this::submit, this::start)
            .forAllStates()
            .name("Upload test document")
            .description("Upload test document for cftlib verification")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);

        new PageBuilder(eventBuilder)
            .page("uploadTestDocument")
            .optional(CaseData::getTestDocument);
    }

    private CaseData start(EventPayload<CaseData, State> payload) {
        return payload.caseData();
    }

    private SubmitResponse submit(EventPayload<CaseData, State> payload) {
        return SubmitResponse.builder()
            .confirmationHeader("Document stored")
            .confirmationBody("Decentralised document metadata saved.")
            .build();
    }
}
