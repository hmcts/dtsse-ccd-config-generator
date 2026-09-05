package uk.gov.hmcts.example.enums;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * An enum whose constant already carries a team-written {@code @CCD}. The label pin must not add a
 * second annotation (which would not compile) nor overwrite the team's own — so the constant is left
 * exactly as it is, which also makes re-applying the patch idempotent.
 */
public enum AnnotatedList {

  @CCD(label = "Team's own label")
  KEPT
}
