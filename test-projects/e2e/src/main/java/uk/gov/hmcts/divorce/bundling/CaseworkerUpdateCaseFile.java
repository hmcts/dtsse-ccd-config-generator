package uk.gov.hmcts.divorce.bundling;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundles;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/**
 * An event that changes the documents on a case tells the SDK, in the event's own transaction,
 * that the case's bundle is out of date. The SDK renders the new one after the event commits.
 */
@Component
public class CaseworkerUpdateCaseFile implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_UPDATE_CASE_FILE = "caseworker-update-case-file";

    @Autowired
    private CaseBundles caseBundles;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_UPDATE_CASE_FILE, this::submit)
            .forAllStates()
            .name("Update case file")
            .description("Re-render the case file bundle with the document-bundling SDK")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(final EventPayload<CaseData, State> payload) {
        caseBundles.changed(CaseFileBundle.NAME, payload.caseReference());
        return SubmitResponse.<State>builder().build();
    }
}
