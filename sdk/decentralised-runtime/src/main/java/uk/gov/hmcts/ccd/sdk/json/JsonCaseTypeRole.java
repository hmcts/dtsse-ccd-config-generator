package uk.gov.hmcts.ccd.sdk.json;

import uk.gov.hmcts.ccd.sdk.api.HasRole;

public enum JsonCaseTypeRole implements HasRole {
  ;

  @Override
  public String getRole() {
    return "";
  }

  @Override
  public String getCaseTypePermissions() {
    return "";
  }
}
