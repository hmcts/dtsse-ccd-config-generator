package uk.gov.hmcts.ccd.sdk.api;

@ComplexType(generate = false)
public interface HasRole {

  String getRole();

  String getCaseTypePermissions();

  /**
   * The organisational access group this role participates in, or {@code null} if none. When
   * present, the SDK derives the matching {@code AccessType} and {@code AccessTypeRole} rows at
   * build time, using this role's {@link #getRole()} as the {@code OrganisationalRoleName}.
   */
  default CCDAccessGroup<?> getAccessGroup() {
    return null;
  }
}
