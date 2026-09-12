package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Exercises {@code @CCD(gate = ...)}. {@link #baseField} is always part of the definition;
 * {@link #gatedField} only when {@code CCD_DEF_JO} resolves to {@code true} at generation time.
 * Both are placed on the same event and tab and search sheet, so the generated
 * CaseField/AuthorisationCaseField/CaseEventToFields/CaseTypeTab/SearchInputFields rows for
 * {@code gatedField} appear or vanish as a unit when the gate flips.
 */
@Data
public class GatedFieldCaseData {

  @CCD(label = "A base field", access = {SolicitorAccess.class})
  private String baseField;

  @CCD(label = "A Judgments-Online field", gate = "CCD_DEF_JO:true", access = {SolicitorAccess.class})
  private String gatedField;
}
