package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.NoGrantsAccess;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;
import uk.gov.hmcts.reform.fpl.access.WriteWithoutReadAccess;

@Data
public class DeclaredFieldAccessCaseData {

  @CCD(label = "Edited by both roles")
  private String sharedField;

  @CCD(label = "Declares its own access", access = {SolicitorAccess.class})
  private String solicitorOnlyPanel;

  @CCD(label = "On a tab, declares nothing")
  private String tabOnlyField;

  @CCD(label = "On a tab scoped to a role it does not name", access = {SolicitorAccess.class})
  private String roleScopedTabField;

  @CCD(label = "On a tab scoped to the role it names", access = {SolicitorAccess.class})
  private String solicitorScopedTabField;

  @CCD(label = "Names an access class that grants nothing", access = {NoGrantsAccess.class})
  private String noGrantsField;

  @CCD(label = "Names an access class granting write but not read",
      access = {WriteWithoutReadAccess.class})
  private String writeWithoutReadField;

  @JsonUnwrapped
  @CCD(access = {SolicitorAccess.class})
  private DeclaredFieldAccessGroup group = new DeclaredFieldAccessGroup();
}
