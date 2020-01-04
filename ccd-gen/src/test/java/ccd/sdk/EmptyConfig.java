package ccd.sdk;

import ccd.sdk.types.BaseCCDConfig;
import ccd.sdk.types.CaseData;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class EmptyConfig extends BaseCCDConfig<CaseData, State, UserRole> {
    @Override
    protected void configure() {
    }
}
