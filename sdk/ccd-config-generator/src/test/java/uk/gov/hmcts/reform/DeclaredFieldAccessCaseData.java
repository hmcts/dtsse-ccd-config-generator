package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.NoGrantsAccess;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

@Data
public class DeclaredFieldAccessCaseData {

  @CCD(label = "Edited by both roles")
  private String sharedField;

  @CCD(label = "Declares its own access", access = {SolicitorAccess.class})
  private String solicitorOnlyPanel;

  @CCD(label = "On a tab, declares nothing")
  private String tabOnlyField;

  @CCD(label = "On a tab scoped to one role", access = {SolicitorAccess.class})
  private String roleScopedTabField;

  @CCD(label = "Names an access class that grants nothing", access = {NoGrantsAccess.class})
  private String noGrantsField;

  @JsonUnwrapped
  @CCD(access = {SolicitorAccess.class})
  private DeclaredFieldAccessGroup group = new DeclaredFieldAccessGroup();
}
