package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.JsonPropertyState.CASE_MANAGEMENT;
import static uk.gov.hmcts.reform.JsonPropertyState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Pins the resolved CCD state IDs when a state enum carries {@code @JsonProperty}. The
 * {@code create} event transitions from {@code Open} (plain constant, ID {@code Open}) to
 * {@code CASE_MANAGEMENT} (ID {@code PREPARE_FOR_HEARING} via {@code @JsonProperty}), so the
 * generated State sheet, the event pre/post condition states, and the AuthorisationCaseState rows
 * must all use the resolved IDs.
 */
@Component
public class JsonPropertyStateCaseType
    implements CCDConfig<JsonPropertyStateCaseData, JsonPropertyState, UserRole> {

  @Override
  public void configure(ConfigBuilder<JsonPropertyStateCaseData, JsonPropertyState, UserRole> builder) {
    builder.caseType("JsonPropertyState", "JsonPropertyState", "@JsonProperty state IDs case type");

    builder.event("create")
        .forStateTransition(Open, CASE_MANAGEMENT)
        .name("Create")
        .grant(CRU, HMCTS_ADMIN)
        .grant(CRU, LOCAL_AUTHORITY);
  }
}
