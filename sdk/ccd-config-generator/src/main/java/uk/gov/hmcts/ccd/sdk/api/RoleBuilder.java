package uk.gov.hmcts.ccd.sdk.api;

public interface RoleBuilder<Role extends HasRole> {
  void has(Role parent);

}
