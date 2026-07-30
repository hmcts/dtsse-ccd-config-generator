package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the {@code AccessTypeRole} row,
 * emitted once per entry in {@link #getOrganisationProfileIds()}. Register them with
 * {@link ConfigBuilder#accessGroups}.</p>
 *
 * <p>Role and field references are {@link HasRole}s and {@link TypedPropertyGetter}s rather than free
 * text, so they cannot drift from the declarations they name; the SDK reads them at build time.
 * Roles named here need not belong to the case's role class, so declaring one does not pull it into
 * the authorisation or case-role generators.</p>
 *
 * @param <T> the case data type
 */
public interface CCDAccessGroup<T> {

  String getAccessTypeId();

  /**
   * Every organisation profile this access type applies to. CCD keys both rows on
   * {@code (AccessTypeID, OrganisationProfileID)}, so one row is emitted per profile.
   */
  List<String> getOrganisationProfileIds();

  boolean isAccessMandatory();

  boolean isAccessDefault();

  boolean isDisplay();

  String getDescription();

  String getHintText();

  int getDisplayOrder();

  /**
   * The organisation-level role this access type grants, or {@code null} to leave
   * {@code OrganisationalRoleName} unset.
   */
  default HasRole getOrganisationalRoleName() {
    return null;
  }

  /**
   * The group role this access type maps to, or {@code null} to leave {@code GroupRoleName} unset —
   * an access type may grant an organisational role without group access.
   */
  default HasRole getGroupRoleName() {
    return null;
  }

  /**
   * The role CCD matches against an organisation policy's {@code OrgPolicyCaseAssignedRole} when
   * deriving case access groups. Mutually exclusive with {@link #getCaseAssignedRoleField()}.
   */
  default HasRole getCaseAssignedRole() {
    return null;
  }

  /**
   * Type-safe reference to the case field holding the assigned organisation policy, resolved to the
   * CCD field id at build time. Mutually exclusive with {@link #getCaseAssignedRole()}.
   */
  default TypedPropertyGetter<T, ?> getCaseAssignedRoleField() {
    return null;
  }

  default boolean isGroupAccessEnabled() {
    return false;
  }

  default String getCaseAccessGroupIdTemplate() {
    return null;
  }

  default String getLiveTo() {
    return null;
  }
}
