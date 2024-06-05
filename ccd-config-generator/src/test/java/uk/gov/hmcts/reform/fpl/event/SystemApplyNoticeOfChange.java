package uk.gov.hmcts.reform.fpl.event;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

@Component
public class SystemApplyNoticeOfChange implements CCDConfig<CaseData, State, UserRole> {

  private static final String APPLY_NOTICE_OF_CHANGE = "noticeOfChangeApplied";

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.event(APPLY_NOTICE_OF_CHANGE)
        .forAllStates()
        .name(APPLY_NOTICE_OF_CHANGE)
        .grant(CRU, HMCTS_ADMIN)
        .aboutToStartCallback(this::aboutToStart)
        .fields();
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToStart(
          CaseDetails<CaseData, State> details) {
    CaseData data = details.getData();
    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(data)
            .build();
  }
}
