package uk.gov.hmcts.reform.fpl.event;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import java.util.HashMap;
import java.util.Map;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

@Component
public class SetSecurityClassification implements CCDConfig<CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.event("setDataAndSecurityClassification")
      .forAllStates()
      .name("Set data and security classification")
      .grant(CRU, HMCTS_ADMIN)
      .aboutToSubmitCallback(this::aboutToSubmit);
  }

  private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
    CaseDetails<CaseData, State> details,
    CaseDetails<CaseData, State> detailsBefore) {
    CaseData d = details.getData();
    Map<String, Object> dataClassification = new HashMap<>();
    dataClassification.put("field1", "PUBLIC");

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
      .data(d)
      .dataClassification(dataClassification)
      .securityClassification("PRIVATE")
      .build();
  }
}
