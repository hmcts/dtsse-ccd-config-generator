package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A nested complex type reached through {@link EventComplexScopeAppeal}. It is placed BOTH as a
 * {@code DisplayContext=COMPLEX} member row of its own (via {@code .complexMember}) and descended
 * into for {@link #code} — the two-rows-for-one-intermediate shape sscs's
 * {@code updateOtherPartyData/appeal} ships.
 */
@Data
@ComplexType(name = "EventComplexScopeBenefit", generate = true)
public class EventComplexScopeBenefit {

  @CCD(label = "Benefit code")
  private String code;
}
