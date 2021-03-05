package uk.gov.hmcts;

import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class ImplementsCCDConfig implements CCDConfig<Object, Object, UserRole> {
    @Override
    public void configure(ConfigBuilder<Object, Object, UserRole> builder) {

    }
}
