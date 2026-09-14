package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type whose members are overridden per event in {@link EventComplexMemberCaseType}.
 * Models the common decentralised-service shape where a shared complex type (e.g. a contact or
 * address block) is re-labelled and conditionally shown within a specific event's page.
 */
@Data
@ComplexType(name = "EventComplexMemberContact", generate = true)
public class EventComplexMemberContact {

  @CCD(label = "Full name")
  private String name;

  @CCD(label = "Email address")
  private String email;

  @CCD(label = "Reference")
  private String reference;

  @CCD(label = "Address")
  private EventComplexMemberNested address;
}
