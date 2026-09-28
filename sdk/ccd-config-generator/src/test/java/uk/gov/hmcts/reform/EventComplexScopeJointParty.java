package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * A complex type held {@code @JsonUnwrapped} with no prefix, so its members are the case type's own
 * top-level fields and their event placements land on {@code CaseEventToFields} rather than
 * {@code CaseEventToComplexTypes}. That is the only sheet carrying
 * {@code ShowSummaryChangeOption}, so it is the only place
 * {@code complexMember}-vs-{@code complexMemberNoSummary} is observable.
 *
 * <p>sscs's {@code jointParty} shape: held prefix-less, and its members placed
 * {@code DisplayContext=COMPLEX} on events.
 */
@Data
public class EventComplexScopeJointParty {

  @CCD(label = "Joint party benefit type")
  private EventComplexScopeBenefit jointPartyBenefitType;

  @CCD(label = "Joint party details")
  private EventComplexScopeBenefit jointPartyDetails;
}
