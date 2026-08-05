package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role {@code OrganisationalRoleName} comes from the
 * {@link HasRole} that attaches to it via {@link HasRole#getAccessGroup()}, so one constant can be
 * shared by several roles.</p>
 *
 * <p>Every role-valued member is a {@link HasRole} rather than a free-text name, so a group
 * configuration cannot reference a role that does not exist. Group roles are deliberately declared
 * in their own enum rather than the case's role class: they take part in no case-type authorisation,
 * so registering them as {@code UserRole}s would emit spurious {@code AuthorisationCaseType} and
 * {@code CaseRoles} rows.</p>
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
   * The group role this access type mints, emitted as {@code GroupRoleName}.
   *
   * <p>The definition store performs <em>no</em> referential check on this column, so a group role
   * with no {@code RoleToAccessProfiles} mapping imports cleanly and then silently grants nothing at
   * runtime. To close that gap the SDK emits the mapping itself, from
   * {@link #getGroupRoleAccessProfiles()}.</p>
   */
  HasRole getGroupRoleName();

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

  /**
   * The access profiles the group role resolves to, used to emit its {@code RoleToAccessProfiles}
   * row. Must be non-empty: without that mapping the minted group role assignment resolves to no
   * access profile and the whole access type is inert.
   */
  List<String> getGroupRoleAccessProfiles();

  boolean isGroupAccessEnabled();

  String getCaseAccessGroupIdTemplate();

  default String getLiveTo() {
    return null;
  }
}
