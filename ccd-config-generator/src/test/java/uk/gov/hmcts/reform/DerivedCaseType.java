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
  // Inject the configuration from our base case type.
  @Autowired
  private List<CCDConfig<CaseData, State, UserRole>> cfgs;

  @Override
  public void configure(ConfigBuilder<DerivedCaseData, State, UserRole> builder) {
    // Apply the configuration of our base case type to our derived type.
    // TODO: Make CCDConfig APIs covariant to avoid this unchecked cast.
    @SuppressWarnings("unchecked")
    var upcast = (ConfigBuilder<CaseData, State, UserRole>)(Object) builder;
    for (var cfg : cfgs) {
      cfg.configure(upcast);
    }

    builder.caseType("derived", "Derived name", "Derived description");
  }
}
