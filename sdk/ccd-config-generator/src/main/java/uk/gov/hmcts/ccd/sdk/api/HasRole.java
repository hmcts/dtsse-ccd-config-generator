package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

@ComplexType(generate = false)
public interface HasRole {

  String getRole();

  String getCaseTypePermissions();

  /**
   * The access profiles this role resolves to in its {@code RoleToAccessProfiles} row, defaulting to
   * the role's own name. Overridden by {@link ConfigBuilder#caseRoleToAccessProfile} when a row is
   * declared explicitly.
   */
  @JsonIgnore
  default List<String> getAccessProfiles() {
    return List.of(getRole());
  }

  /**
   * The organisational access groups this role participates in, empty if none. For each one the SDK
   * derives the matching {@code AccessType} and {@code AccessTypeRole} rows at build time, naming
   * this role in whichever of {@code GroupRoleName} or {@code OrganisationalRoleName} the group's
   * {@link CCDAccessGroup#isGroupAccessEnabled()} selects, plus its {@code RoleToAccessProfiles} row.
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
