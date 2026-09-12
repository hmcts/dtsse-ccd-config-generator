package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * State enum exercising {@code @CCD(ignore = true)} on a state constant. A service reusing its own
 * {@code State} enum routinely has constants no case type declares — here {@code Unknown}, a
 * sentinel its runtime code still needs, and {@code LegacyComposite} — which must contribute no
 * {@code State} row and no {@code AuthorisationCaseState} row.
 *
 * <p>{@code Unknown} is named by the {@code create} event's state transition, so it exercises
 * suppression of a grant derived from event permissions; {@code LegacyComposite} carries an
 * {@code access} class, so it exercises suppression of a grant declared on the constant itself.
 * {@code Open} and {@code CaseManagement} are ordinary states and must be unaffected.
 */
public enum IgnoredStateState {
  @CCD(label = "Open state")
  Open,

  @CCD(label = "Case management state")
  CaseManagement,

  @CCD(ignore = true)
  Unknown,

  @CCD(ignore = true, access = {SolicitorAccess.class})
  LegacyComposite
}
