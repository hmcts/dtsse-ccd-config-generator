package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link GatedMemberCaseType}. {@link #complexField} is an ungated complex field so
 * its {@code GatedMemberComplex} type is always discovered; the gate lives on a <em>member</em> of
 * that complex type (see {@link GatedMemberComplex}), not on any CaseData field here.
 */
@Data
public class GatedMemberCaseData {

  @CCD(label = "A base field", access = {SolicitorAccess.class})
  private String baseField;

  @CCD(label = "A complex field", access = {SolicitorAccess.class})
  private GatedMemberComplex complexField;
}
