package uk.gov.hmcts.reform;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A shuttered case type used to exercise {@link ConfigBuilder#shutterService()} and
 * {@link ConfigBuilder#shutterServiceExclude(uk.gov.hmcts.ccd.sdk.api.HasRole...)}.
 * The whole service is shuttered but the system-update role is excluded, so it retains
 * its normal permissions while every other role is set to DELETE.
 */
@Component
public class ShutteredCaseType implements CCDConfig<ShutteredCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<ShutteredCaseData, State, UserRole> builder) {
    builder.caseType("Shuttered", "Shuttered", "Shuttered case type");
    builder.shutterService();
    builder.shutterServiceExclude(UserRole.SYSTEM_UPDATE);
  }
}
