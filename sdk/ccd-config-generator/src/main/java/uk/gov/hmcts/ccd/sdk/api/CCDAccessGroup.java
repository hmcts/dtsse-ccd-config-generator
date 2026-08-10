package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role {@code OrganisationalRoleName} comes from the
 * {@link HasRole} that attaches to it via {@link HasRole#getAccessGroups()}, so one constant can be
 * shared by several roles, and one role can carry several constants.</p>
 *
 * <p>An access type is identified by {@code (accessTypeId, organisationProfileId)}, so the same
 * logical access type offered to several organisation profiles needs one constant per profile,
 * all of them typically attached to the same role.</p>
 *
 * <p>Every role-valued member is a {@link HasRole} rather than a free-text name, so a group
 * configuration cannot reference a role that does not exist. All of them — the organisational role
 * that carries the group, the group role it mints, and the case role naming the OrganisationPolicy —
 * belong in the case's role class. Only that class is iterated to derive access types, so a role
 * declared elsewhere is silently ignored; and the group role needs the {@code AuthorisationCaseType}
 * row that role-class membership produces, because CCD checks the case type ACL before the event ACL.
 * Group roles are ordinary organisational roles that happen to be minted per organisation by PRM.</p>
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
   *
   * <p><strong>Implement this as a method, not a constructor argument</strong>, for the reason given
   * on {@link #getCaseAssignedRoleField()}.</p>
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
   * <p><strong>Implement this as a method, not a constructor argument.</strong> The role returned
   * here lives in the case's role class, which in turn references this access group via
   * {@link HasRole#getAccessGroups()} — a circular static initialisation. A role captured in a
   * constructor argument reads {@code null} in whichever class initialises second, because the JVM
   * does not re-enter an in-progress {@code <clinit>}. A method body is not evaluated until build
   * time, by which point both enums are fully initialised:</p>
   *
   * <pre>{@code
   * @Override
   * public HasRole getCaseAssignedRoleField() {
   *   return UserRole.CCD_SOLICITOR;
   * }
   * }</pre>
   *
   * <p>Constants needing different roles can {@code switch (this)} in that body.</p>
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
