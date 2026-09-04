package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * A superclass whose {@code sharedDetail} {@link IgnoredReachShared} redeclares, so this declaration
 * is hidden: Java resolves the name to the subclass's field and Jackson sees one property. Reflection
 * over the class hierarchy reaches both, and the hidden one must emit no row of its own.
 *
 * <p>The shape is sscs's: {@code JointParty} redeclares the {@code id}/{@code identity}/{@code
 * name}/{@code address}/{@code contact} it inherits from {@code Entity} purely to give each a
 * {@code @JsonProperty("jointParty…")} ID, and the hidden {@code Entity} declarations emitted a
 * second row apiece under their own unprefixed IDs.
 */
@Data
public abstract class IgnoredReachBase {

  @CCD(label = "The hidden declaration")
  private String sharedDetail;
}
