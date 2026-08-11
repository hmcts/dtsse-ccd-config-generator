package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role {@code OrganisationalRoleName} or {@code GroupRoleName}
 * comes from the {@link HasRole} that attaches to it via {@link HasRole#getAccessGroup()}: when
 * {@link #isGroupAccessEnabled()} is {@code true} the role's name is emitted as
 * {@code GroupRoleName}; otherwise it is emitted as {@code OrganisationalRoleName}.</p>
 */
public interface CCDAccessGroup {

  String getAccessTypeId();

  String getOrganisationProfileId();

  boolean isAccessMandatory();

  boolean isAccessDefault();

  boolean isDisplay();

  String getDescription();

  String getHintText();

  int getDisplayOrder();

  /**
   * The case role identifying which {@code OrganisationPolicy} supplies the organisation ID,
   * emitted as {@code CaseAssignedRoleField}.
   *
   * <p>Despite the column name this is a <strong>role name, not a field name</strong>. At runtime
   * {@code CaseAccessGroupUtils} scans case data for the {@code OrganisationPolicy} whose
   * {@code OrgPolicyCaseAssignedRole} equals this value, and at import time the definition store
   * validates it against {@code RoleToAccessProfiles.RoleName}. It is therefore the same role
   * carried by {@link uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy#getOrgPolicyCaseAssignedRole()} —
   * typically a bracketed case role such as {@code [SOLICITOR]}.</p>
   *
   * <p><strong>Resolve this lazily.</strong> The role returned here normally lives in the case's role
   * class, which in turn references this access group via {@link HasRole#getAccessGroup()} — a
   * circular static initialisation. If an implementing enum captures the role in a constructor
   * argument, whichever class initialises second reads {@code null}, because the JVM does not re-enter
   * an in-progress {@code <clinit>}. Override this method on the constant instead, so the reference is
   * read at build time when both enums are fully initialised:</p>
   *
   * <pre>{@code
   * MY_ACCESS_TYPE("SOLICITOR_PROFILE", ...) {
   *   @Override
   *   public HasRole getCaseAssignedRoleField() {
   *     return UserRole.CCD_SOLICITOR;
   *   }
   * }
   * }</pre>
   */
  HasRole getCaseAssignedRoleField();

  Boolean isGroupAccessEnabled();

  String getCaseAccessGroupIdTemplate();

  default String getLiveTo() {
    return null;
  }
}
