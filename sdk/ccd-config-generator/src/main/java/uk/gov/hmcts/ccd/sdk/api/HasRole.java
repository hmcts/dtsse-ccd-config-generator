package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

@ComplexType(generate = false)
public interface HasRole {

  String getRole();

  String getCaseTypePermissions();

  /**
   * The organisational access groups this role participates in, empty if none. For each one the SDK
   * derives the matching {@code AccessType} and {@code AccessTypeRole} rows at build time, using
   * this role's {@link #getRole()} as the {@code OrganisationalRoleName}.
   *
   * <p>This is a collection because an access type is identified by
   * {@code (accessTypeId, organisationProfileId)}, not by {@code accessTypeId} alone: the same
   * logical access type offered to several organisation profiles is several rows, and one role
   * commonly carries all of them. Holding them as a varargs constructor argument reads naturally:</p>
   *
   * <pre>{@code
   * CLAIMANT("claimant", CRU, LOCAL_AUTHORITY_CLAIMANT_ACCESS, CHARITY_ORG_CLAIMANT_ACCESS),
   * }</pre>
   */
  default List<CCDAccessGroup> getAccessGroups() {
    return List.of();
  }
}
