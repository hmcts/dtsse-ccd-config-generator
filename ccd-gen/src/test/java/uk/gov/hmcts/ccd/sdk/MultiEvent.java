package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.ccd.sdk.types.CCDConfig;
import uk.gov.hmcts.ccd.sdk.types.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class MultiEvent implements CCDConfig<CaseData, State, UserRole> {
    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {

//        builder.event("judgeDetails")
//                .forStates(State.PREPARE_FOR_HEARING, State.Open);
    }
}
