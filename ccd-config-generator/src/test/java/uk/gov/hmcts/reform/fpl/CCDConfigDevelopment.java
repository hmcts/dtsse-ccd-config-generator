package uk.gov.hmcts.reform.fpl;

public class CCDConfigDevelopment extends CCDConfig {

    @Override
    protected String environment() {
        return "development";
    }
}
