package uk.gov.hmcts.reform.fpl;

import uk.gov.hmcts.ccd.sdk.api.Webhook;

public class CCDConfigDevelopment extends FPLConfig {

    @Override
    protected String environment() {
        return "development";
    }
}
