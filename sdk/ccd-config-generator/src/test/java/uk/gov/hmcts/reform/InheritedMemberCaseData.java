package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link InheritedMemberCaseType}: three parties over one shared base, reached both as
 * complex fields and — for the joint party — {@code @JsonUnwrapped} with no prefix, so its inherited
 * members become top-level {@code CaseField} rows. That is the path on which sscs's dropped member
 * has to actually disappear.
 */
@Data
public class InheritedMemberCaseData {

  @CCD(label = "A base field", access = {SolicitorAccess.class})
  private String baseField;

  @CCD(label = "Appellant", access = {SolicitorAccess.class})
  private InheritedMemberAppellant appellant;

  @CCD(label = "Representative", access = {SolicitorAccess.class})
  private InheritedMemberRepresentative representative;

  @JsonUnwrapped
  @CCD(access = {SolicitorAccess.class})
  private InheritedMemberJointParty jointParty;
}
