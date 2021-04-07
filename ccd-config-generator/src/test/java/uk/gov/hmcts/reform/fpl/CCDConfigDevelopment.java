package uk.gov.hmcts.reform.fpl;

import uk.gov.hmcts.ccd.sdk.api.Webhook;

public class CCDConfigDevelopment extends FPLConfig {

    @Override
    protected String webhookConvention(Webhook webhook, String eventId) {
        return "localhost:5050/" + eventId + "/" + webhook;
    }

    @Override
    protected String environment() {
        return "development";
    }
}
