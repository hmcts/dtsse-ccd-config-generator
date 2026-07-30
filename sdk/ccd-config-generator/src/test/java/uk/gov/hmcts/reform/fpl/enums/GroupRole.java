package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.HasRole;

/**
 * Organisational group roles referenced by {@link AccessGroups#getGroupRoleName()}.
 *
 * <p>These are {@link HasRole}s so the reference is type-safe, but they are deliberately a separate
 * enum from {@link UserRole} (the case's role class). Group roles do not participate in case-type
 * authorisations, so keeping them out of the role class means they are not iterated by the
 * authorisation / case-role generators.</p>
 */
public enum GroupRole implements HasRole {

  CASE_ACCESS_APPROVER_GROUP("caseworker-approver-group");

  private final String role;

  GroupRole(String role) {
    this.role = role;
  }

  @Override
  public String getRole() {
    return role;
  }

  @Override
  public String getCaseTypePermissions() {
    return "";
  }
}
