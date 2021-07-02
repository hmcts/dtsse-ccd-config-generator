package uk.gov.hmcts.test.library;

import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import org.springframework.stereotype.Component;

@Component
public class CCDConfig implements uk.gov.hmcts.ccd.sdk.api.CCDConfig<CaseData, State, Role> {
  @Override
  public void configure(ConfigBuilder<CaseData, State, Role> builder) {
    builder.caseType("test", "test", "test");
  }
}
