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

@Component
@Slf4j
public class CaseworkerSetAField implements CCDConfig<CaseData, State, UserRole> {
    public static final String CASEWORKER_SET_A_FIELD = "caseworker-set-a-field";

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(CASEWORKER_SET_A_FIELD)
            .forAllStates()
            .name("Set aField")
            .description("Set the new aField value")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, SUPER_USER, CASE_WORKER))
            .page("setAField")
            .pageLabel("Set aField value")
            .optional(CaseData::getAField);
    }
}
