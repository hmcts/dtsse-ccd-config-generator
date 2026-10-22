package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.UnwrappedCollisionState.Open;
import static uk.gov.hmcts.reform.UnwrappedCollisionState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Pins that a field whose CCD ID collides with an {@code @JsonUnwrapped} container's Java member name
 * still gets its {@code AuthorisationCaseField} rows.
 *
 * <p>{@code caseOutcome} is both the container's member name and — because the container is
 * prefix-less — the ID of a real leaf inside it. A suppression keyed on the member name alone
 * therefore discarded every grant on that leaf while emitting its {@code CaseField} row, leaving a
 * field no role could read. {@code didPoAttend}, its sibling with the identical access class, was
 * unaffected: it is the control that isolates the collision as the cause.
 *
 * <p>The event enters the holder scope so the members are placed on {@code CaseEventToFields}, the
 * shape a prefix-less holder gives — {@code .complex(getter)} on the holder registers no field of its
 * own, which is also how the container's name reaches an ID-keyed table at all.
 */
@Component
public class UnwrappedCollisionCaseType
    implements CCDConfig<UnwrappedCollisionCaseData, UnwrappedCollisionState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<UnwrappedCollisionCaseData, UnwrappedCollisionState, UserRole> builder) {
    builder.caseType(
        "UnwrappedCollision", "Unwrapped collision",
        "A field ID colliding with an @JsonUnwrapped container's member name");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(UnwrappedCollisionCaseData::getPlainField)
        .complex(UnwrappedCollisionCaseData::getCaseOutcome)
          .optional(UnwrappedCollisionOutcome::getCaseOutcome)
          .optional(UnwrappedCollisionOutcome::getDidPoAttend)
        .done();
  }
}
