package uk.gov.hmcts.ccd.sdk.api;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role name comes from the {@link HasRole} that attaches to it
 * via {@link HasRole#getAccessGroups()}: when {@link #isGroupAccessEnabled()} is {@code true} that
 * role's name is emitted as {@code GroupRoleName}, otherwise as {@code OrganisationalRoleName}. So
 * one constant can be shared by several roles, and one role can carry several constants.</p>
 *
 * <p>An access type is identified by {@code (accessTypeId, organisationProfileId)}, so the same
 * logical access type offered to several organisation profiles needs one constant per profile,
 * all of them typically attached to the same role.</p>
 *
 * <p>No member of this interface is role-typed. An access group is referenced <em>by</em> the case's
 * role class, so referring back to it here would make the two enums circularly initialised: a role
 * captured as a constructor argument reads {@code null} in whichever class initialises second,
 * because the JVM does not re-enter an in-progress {@code <clinit>}. Keeping every member a plain
 * value means an implementation can be a straightforward enum with all fields set from constructor
 * arguments, and needs no lazily-resolving method bodies or per-constant overrides.</p>
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
   * {@code OrgPolicyCaseAssignedRole} equals this value. It is therefore the same role carried by
   * {@link uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy#getOrgPolicyCaseAssignedRole()} — typically
   * a bracketed case role such as {@code [SOLICITOR]}.</p>
   *
   * <p>Declared as a {@code String} rather than a {@link HasRole} to keep this interface free of
   * references back into the role class; see the note on the type. The role is still checked, just
   * at import rather than compile time: the definition store validates this column against
   * {@code RoleToAccessProfiles.RoleName}, so the role needs a
   * {@link ConfigBuilder#caseRoleToAccessProfile} row.</p>
   */
  String getCaseAssignedRoleField();

  /**
   * Whether this access type mints a group role per organisation.
   *
   * <p>When {@code true} the attaching role's name is emitted as {@code GroupRoleName}; the
   * definition store requires the two to go together, rejecting a {@code GroupRoleName} whose
   * {@code GroupAccessEnabled} is not set. When {@code false} the name is emitted as
   * {@code OrganisationalRoleName} instead.</p>
   */
  boolean isGroupAccessEnabled();

  String getCaseAccessGroupIdTemplate();

  default String getLiveTo() {
    return null;
  }
}
