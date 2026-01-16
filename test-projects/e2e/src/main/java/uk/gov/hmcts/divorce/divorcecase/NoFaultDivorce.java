package uk.gov.hmcts.divorce.divorcecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.APPLICANT_1_SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.APPLICANT_2_SOLICITOR;

@Component
@Slf4j
public class NoFaultDivorce implements CCDConfig<CaseData, State, UserRole> {

    private static final String CASE_TYPE = "E2E";
    public static final String CASE_TYPE_DESCRIPTION = "New Law Case";
    public static final String JURISDICTION = "DIVORCE";

    public static String getCaseType() {
        return CASE_TYPE;
    }

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder.setCallbackHost("http://localhost:4013");

        configBuilder.caseType(getCaseType(), CASE_TYPE_DESCRIPTION, "Handling of the dissolution of marriage");
        configBuilder.jurisdiction(JURISDICTION, "Family Divorce", "Family Divorce: dissolution of marriage");
        configBuilder.omitHistoryForRoles(APPLICANT_1_SOLICITOR, APPLICANT_2_SOLICITOR);
        configBuilder.tab("notes", "Case notes")
            .field(CaseData::getNotes)
                .forRoles(UserRole.values());

        // to shutter the service within xui uncomment this line
        // configBuilder.shutterService();
        log.info("Building definition for local cftlib e2e tests");
    }
}
