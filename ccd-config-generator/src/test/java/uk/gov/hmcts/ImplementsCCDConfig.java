package uk.gov.hmcts;

import uk.gov.hmcts.ccd.sdk.types.CCDConfig;
import uk.gov.hmcts.ccd.sdk.types.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class ImplementsCCDConfig implements CCDConfig<Object, State, UserRole> {
    @Override
    public void configure(ConfigBuilder<Object, State, UserRole> builder) {

    }
}
