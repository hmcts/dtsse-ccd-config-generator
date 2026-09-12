package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A nested complex type reached through {@link EventComplexMemberContact}. Its member carries a
 * per-event label to prove {@code EventToComplexTypes.EventElementLabel} is emitted with the
 * dotted {@code ListElementCode} of a nested member.
 */
@Data
@ComplexType(name = "EventComplexMemberNested", generate = true)
public class EventComplexMemberNested {

  @CCD(label = "A postcode")
  private String postcode;
}
