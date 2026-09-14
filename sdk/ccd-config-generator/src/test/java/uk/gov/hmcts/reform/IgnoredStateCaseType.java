package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.IgnoredStateState.CaseManagement;
import static uk.gov.hmcts.reform.IgnoredStateState.Open;
import static uk.gov.hmcts.reform.IgnoredStateState.Unknown;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Pins {@code @CCD(ignore = true)} on a state constant: the suppressed constants emit no
 * {@code State} row and no {@code AuthorisationCaseState} row, while the ordinary states round-trip
 * unchanged.
 *
 * <p>The {@code reopen} event deliberately transitions <em>to</em> the ignored {@code Unknown}
 * state: without suppression at the emit loop that grant would still reach
 * {@code AuthorisationCaseState}, naming a state the {@code State} sheet no longer declares — a row
 * the definition-store importer would reject.
 */
@Component
public class IgnoredStateCaseType
    implements CCDConfig<IgnoredStateCaseData, IgnoredStateState, UserRole> {

  @Override
  public void configure(ConfigBuilder<IgnoredStateCaseData, IgnoredStateState, UserRole> builder) {
    builder.caseType("IgnoredState", "IgnoredState", "Ignored state case type");

    builder.event("create")
        .forStateTransition(Open, CaseManagement)
        .name("Create")
        .grant(CRU, HMCTS_ADMIN);

    builder.event("reopen")
        .forStateTransition(CaseManagement, Unknown)
        .name("Reopen")
        .grant(CRU, HMCTS_ADMIN);
  }
}
