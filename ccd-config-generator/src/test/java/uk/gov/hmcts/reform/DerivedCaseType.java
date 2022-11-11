package uk.gov.hmcts.reform;


import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class DerivedCaseType implements CCDConfig<DerivedCaseData, State, UserRole> {
  @Override
  public void configure(ConfigBuilder<DerivedCaseData, State, UserRole> builder) {
    builder.caseType("derived", "foo", "bar");
  }
}
