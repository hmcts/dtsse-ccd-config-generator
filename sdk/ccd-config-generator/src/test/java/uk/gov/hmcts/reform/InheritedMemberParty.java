package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * A shared base declaring the members several parties have in common — sscs's abstract {@code Entity}
 * exactly. Each member is declared ONCE, so a field-level {@code @CCD} says one thing for every
 * subclass at once; the subclasses override per-subclass with class-level {@code @CCD(member)}.
 */
@Data
public abstract class InheritedMemberParty {

  @CCD(label = "Name")
  private String partyName;

  @CCD(label = "Organisation")
  private String organisation;
}
