package uk.gov.hmcts.reform.typedsearch;

import uk.gov.hmcts.ccd.sdk.api.HasRole;

public enum TypedSearchRole implements HasRole {
  CASEWORKER("caseworker-typed-search");

  private final String role;

  TypedSearchRole(String role) {
    this.role = role;
  }

  @Override
  public String getRole() {
    return role;
  }

  @Override
  public String getCaseTypePermissions() {
    return "CRUD";
  }
}
