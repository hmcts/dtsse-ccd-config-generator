package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.IgnoredReachState.Open;
import static uk.gov.hmcts.reform.IgnoredReachState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type whose case data reaches one complex type only through ignored fields and another
 * through both an ignored and a live field. The snapshot pins that the first emits no
 * {@code ComplexTypes} rows at all while the second is unaffected — complex-type reachability
 * honours {@code @CCD(ignore = true)}/{@code @JsonIgnore} exactly as it honours an inactive
 * {@code @CCD(gate)}.
 */
@Component
public class IgnoredReachCaseType
    implements CCDConfig<IgnoredReachCaseData, IgnoredReachState, UserRole> {

  @Override
  public void configure(ConfigBuilder<IgnoredReachCaseData, IgnoredReachState, UserRole> builder) {
    builder.caseType("IgnoredReach", "Ignored reach", "Ignored-field reachability case type");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(IgnoredReachCaseData::getBaseField)
        .optional(IgnoredReachCaseData::getSharedField);
  }
}
