package uk.gov.hmcts.divorce.sow014.nfd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

@Component
@Slf4j
public class CaseworkerMaintainCaseLink implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_MAINTAIN_CASE_LINK = "caseworker-maintain-case-link";
    private static final String ALWAYS_HIDE = "LinkedCasesComponentLauncher = \"DONOTSHOW\"";

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(CASEWORKER_MAINTAIN_CASE_LINK)
            .forAllStates()
            .name("Maintain linked cases")
            .description("Maintain linked cases")
            .grant(CREATE_READ_UPDATE, CASE_WORKER)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .showEventNotes())
            .page("maintainCaseLinks")
            .optional(CaseData::getCaseLinks, ALWAYS_HIDE, null, true)
            .optional(CaseData::getLinkedCasesComponentLauncher,
                null, null, null, null, "#ARGUMENT(UPDATE,LinkedCases)");
    }
}
