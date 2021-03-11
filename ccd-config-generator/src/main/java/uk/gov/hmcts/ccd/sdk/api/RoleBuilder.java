package uk.gov.hmcts.ccd.sdk.api;

public interface RoleBuilder<Role extends HasRole> {
  void has(Role parent);

  // The role(s) don't use Caseworker UI.
  // Access will not be granted to fields in UI tabs.
  void setApiOnly();

}
