package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A scalar complex type whose members are overridden per event in
 * {@link EventComplexScopeCaseType}. Models sscs's {@code appeal} / {@code presentingOfficersDetails}
 * shape: a nested block ({@link #benefitType}) that the definition places as a
 * {@code DisplayContext=COMPLEX} member row in its own right, alongside a leaf inside it.
 */
@Data
@ComplexType(name = "EventComplexScopeAppeal", generate = true)
public class EventComplexScopeAppeal {

  @CCD(label = "Benefit type")
  private EventComplexScopeBenefit benefitType;

  @CCD(label = "Appellant reference")
  private String reference;
}
