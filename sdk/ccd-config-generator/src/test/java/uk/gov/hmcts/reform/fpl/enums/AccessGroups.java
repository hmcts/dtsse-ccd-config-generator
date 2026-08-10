package uk.gov.hmcts.reform.fpl.enums;

import java.util.List;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

/**
 * Organisational access groups declared as enum constants. A role attaches to one of these via
 * {@link UserRole#getAccessGroup()}; the SDK then derives the AccessType + AccessTypeRole rows, plus
 * the group role's RoleToAccessProfiles row.
 *
 * <p>Both role-valued members point back into {@link UserRole}, which references this enum, so both
 * are resolved by a per-constant override rather than a constructor argument: a constructor argument
 * would be read during the circular static initialisation and come out null. {@code groupRoleName} is
 * the role PRM mints per organisation; {@code caseAssignedRoleField} is the case role carried by the
 * OrganisationPolicy's {@code OrgPolicyCaseAssignedRole} — a role name, not a field name, despite the
 * column's title.</p>
 */
@Getter
public enum AccessGroups implements CCDAccessGroup {

  SOLICITOR_ORG_POLICY(
      "SOLICITOR_PROFILE",
      true,
      true,
      true,
      "Solicitor access type description",
      "Solicitor access type hint",
      1,
      List.of("access-profile"),
      true,
      "PUBLICLAW:CARE_SUPERVISION_EPO:caseworker-approver-group:$ORGID$") {
    @Override
    public HasRole getGroupRoleName() {
      return UserRole.CASE_ACCESS_APPROVER_GROUP;
    }

    @Override
    public HasRole getCaseAssignedRoleField() {
      return UserRole.CCD_SOLICITOR;
    }
  };

  private final String organisationProfileId;
  private final boolean accessMandatory;
  private final boolean accessDefault;
  private final boolean display;
  private final String description;
  private final String hintText;
  private final int displayOrder;
  private final List<String> groupRoleAccessProfiles;
  private final boolean groupAccessEnabled;
  private final String caseAccessGroupIdTemplate;

  AccessGroups(String organisationProfileId, boolean accessMandatory, boolean accessDefault,
               boolean display, String description, String hintText, int displayOrder,
               List<String> groupRoleAccessProfiles, boolean groupAccessEnabled,
               String caseAccessGroupIdTemplate) {
    this.organisationProfileId = organisationProfileId;
    this.accessMandatory = accessMandatory;
    this.accessDefault = accessDefault;
    this.display = display;
    this.description = description;
    this.hintText = hintText;
    this.displayOrder = displayOrder;
    this.groupRoleAccessProfiles = groupRoleAccessProfiles;
    this.groupAccessEnabled = groupAccessEnabled;
    this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
  }

  @Override
  public String getAccessTypeId() {
    return name();
  }
}
