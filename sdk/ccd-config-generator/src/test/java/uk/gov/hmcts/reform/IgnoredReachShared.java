package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type reached through BOTH an ignored and a live field (see
 * {@link IgnoredReachCaseData}). It must keep emitting its {@code ComplexTypes} rows: filtering
 * ignored fields out of reachability may only drop a type nothing else reaches, never one a live
 * field still references.
 */
@Data
@ComplexType(name = "IgnoredReachShared", generate = true)
public class IgnoredReachShared {

  /** A static on a complex type — the sscs shape exactly; see {@link IgnoredReachCaseData}. */
  private static final int NOT_A_MEMBER = 1;

  @CCD(label = "A shared detail")
  private String sharedDetail;
}
