package uk.gov.hmcts.test.library;

import uk.gov.hmcts.ccd.sdk.api.HasRole;

public enum Role implements HasRole {
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
