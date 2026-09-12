package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.ccd.sdk.api.Permission.R;
import static uk.gov.hmcts.reform.ExplicitState.Open;
import static uk.gov.hmcts.reform.ExplicitState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises {@link ConfigBuilder#explicitStateGrants()}. The events below grant broad permissions
 * that would normally be derived onto their states, but because the builder opts into explicit
 * state grants only the narrow {@link ConfigBuilder#grant} rows below are emitted.
 */
@Component
public class ExplicitStateGrantsCaseType
    implements CCDConfig<ExplicitStateGrantsCaseData, ExplicitState, UserRole> {

  @Override
  public void configure(ConfigBuilder<ExplicitStateGrantsCaseData, ExplicitState, UserRole> builder) {
    builder.caseType("ExplicitStateGrants", "ExplicitStateGrants", "Explicit state grants case type");
    builder.explicitStateGrants();

    // These event grants would derive broad AuthorisationCaseState permissions if derivation were
    // enabled: CRU for both roles on the destination state, plus the C-granting transition would
    // additionally grant CRU on the Open pre-state and R on the Submitted post-state.
    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, HMCTS_ADMIN)
        .grant(CRU, LOCAL_AUTHORITY);

    builder.event("edit")
        .forState(Submitted)
        .name("Edit")
        .grant(CRU, HMCTS_ADMIN)
        .grant(CRU, LOCAL_AUTHORITY);

    // Only these narrow explicit rows should appear in AuthorisationCaseState.
    builder.grant(Open, Set.of(R), LOCAL_AUTHORITY);
    builder.grant(Submitted, Set.of(R), HMCTS_ADMIN);
  }
}
