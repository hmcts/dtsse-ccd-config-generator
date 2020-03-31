package uk.gov.hmcts.ccd.sdk.types;

@FunctionalInterface
public interface RoleBuilder<Role extends HasRole> {
  void has(Role parent);
}
