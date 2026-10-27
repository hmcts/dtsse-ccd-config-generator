package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

@Data
public class DeclaredFieldAccessCaseData {

  @CCD(label = "Edited by both roles")
  private String sharedField;

  @CCD(label = "Declares its own access", access = {SolicitorAccess.class})
  private String solicitorOnlyPanel;

  @CCD(label = "On a tab, declares nothing")
  private String tabOnlyField;

  @CCD(label = "On a tab scoped to one role")
  private String roleScopedTabField;
}
