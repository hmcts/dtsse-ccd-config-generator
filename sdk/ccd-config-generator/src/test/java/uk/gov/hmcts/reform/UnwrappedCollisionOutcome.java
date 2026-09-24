package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * sscs's {@code CaseOutcome} shape verbatim: a complex type held {@code @JsonUnwrapped} under the
 * member name {@code caseOutcome}, which itself declares a leaf named {@code caseOutcome}. Because
 * the holder is prefix-less, that leaf's CCD field ID is also {@code caseOutcome} — so the container's
 * Java member name and a real field's CCD ID collide.
 *
 * <p>{@link #didPoAttend} is the control: the same access class, the same container, the same
 * reflection path, differing only in that its ID collides with nothing.
 */
@Data
public class UnwrappedCollisionOutcome {

  @CCD(label = "Case outcome", access = {SolicitorAccess.class})
  private String caseOutcome;

  @CCD(label = "Did PO attend", access = {SolicitorAccess.class})
  private String didPoAttend;
}
