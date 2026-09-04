package uk.gov.hmcts.reform;

import lombok.Data;
import lombok.EqualsAndHashCode;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type reached through BOTH an ignored and a live field (see
 * {@link IgnoredReachCaseData}). It must keep emitting its {@code ComplexTypes} rows: filtering
 * ignored fields out of reachability may only drop a type nothing else reaches, never one a live
 * field still references.
 *
 * <p>It also redeclares its superclass's {@code sharedDetail}, so exactly one row is emitted for the
 * property, carrying THIS declaration's label — see {@link IgnoredReachBase}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ComplexType(name = "IgnoredReachShared", generate = true)
public class IgnoredReachShared extends IgnoredReachBase {

  /** A static on a complex type — the sscs shape exactly; see {@link IgnoredReachCaseData}. */
  private static final int NOT_A_MEMBER = 1;

  @CCD(label = "A shared detail")
  private String sharedDetail;
}
