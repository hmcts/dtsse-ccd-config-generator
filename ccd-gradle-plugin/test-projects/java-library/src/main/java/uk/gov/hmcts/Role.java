package uk.gov.hmcts;

import uk.gov.hmcts.ccd.sdk.api.HasCaseTypePerm;

public enum Role implements HasCaseTypePerm {
  Foo;

  @Override
  public String getRole() {
    return "foo";
  }

  @Override
  public String getCaseTypePermissions() {
    return "R";
  }
}
