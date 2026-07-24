package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.StateDescriptionState.CaseManagement;
import static uk.gov.hmcts.reform.StateDescriptionState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Pins {@code @CCD#description()} on a state constant: {@code CaseManagement}'s {@code Description}
 * column must be the explicit description, distinct from its {@code Name}; {@code Open}'s must still
 * default to its {@code Name} (today's unchanged behaviour).
 */
@Component
public class StateDescriptionCaseType
    implements CCDConfig<StateDescriptionCaseData, StateDescriptionState, UserRole> {

  @Override
  public void configure(ConfigBuilder<StateDescriptionCaseData, StateDescriptionState, UserRole> builder) {
    builder.caseType("StateDescription", "StateDescription", "State description case type");

    builder.event("create")
        .forStateTransition(Open, CaseManagement)
        .name("Create")
        .grant(CRU, HMCTS_ADMIN);
  }
}
