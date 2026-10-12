package uk.gov.hmcts.reform;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises {@link ConfigBuilder#banner}.
 */
@Component
public class BannerFeatureCaseType implements CCDConfig<BannerFeatureCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<BannerFeatureCaseData, State, UserRole> builder) {
    builder.caseType("BannerFeature", "BannerFeature", "Banner feature case type");
    builder.banner(true, "Your system might be running slowly.",
        "https://status.example.com", "Check service status");
  }
}
