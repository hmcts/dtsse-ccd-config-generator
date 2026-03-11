package uk.gov.hmcts.reform.optionallist;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class OptionalListConfig implements CCDConfig<OptionalListCaseData, State, UserRole> {

  private static final String ALWAYS_HIDE = "flagLauncher = \"ALWAYS_HIDE\"";

  @Override
  public void configure(ConfigBuilder<OptionalListCaseData, State, UserRole> builder) {
    builder.caseType("OptionalList", "Optional List", "Optional list test case type");
    builder.event("createFlags")
        .forAllStates()
        .name("Create Flag")
        .grant(CRU, HMCTS_ADMIN)
        .fields()
        .page("1")
        .optionalList(OptionalListCaseData::getParties, ALWAYS_HIDE, true)
        .done();
  }
}
