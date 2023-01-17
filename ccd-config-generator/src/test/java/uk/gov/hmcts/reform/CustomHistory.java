package uk.gov.hmcts.reform;


import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Component
public class CustomHistory implements CCDConfig<CustomHistory, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CustomHistory, State, UserRole> builder) {
    builder.caseType("CustomHistory", "", "");
    builder.tab("First", "First tab")
      .field("aField");
    builder.tab("CaseHistory", "History")
      .field("caseHistory");
  }
}
