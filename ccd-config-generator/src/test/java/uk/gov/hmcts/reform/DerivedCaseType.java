package uk.gov.hmcts.reform;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import java.util.List;

@Component
public class DerivedCaseType implements CCDConfig<DerivedCaseData, State, UserRole> {
  @Autowired
  private List<CCDConfig<CaseData, State, UserRole>> cfgs;

  @Override
  @SuppressWarnings("unchecked")
  public void configure(ConfigBuilder<DerivedCaseData, State, UserRole> builder) {
    ConfigBuilder<CaseData, State, UserRole> upcast = (ConfigBuilder<CaseData, State, UserRole>)(Object) builder;
    for (CCDConfig<CaseData, State, UserRole> cfg : cfgs) {
      cfg.configure(upcast);
    }

    builder.caseType("derived", "foo", "bar");
  }
}
