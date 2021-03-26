package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.HasCaseRole;

public enum CaseRole implements HasCaseRole {

  FOO;

  public String getRole() {
    return "role";
  }

  public String getName() {
    return "name";
  }

  public String getDescription() {
    return "description";
  }
}
