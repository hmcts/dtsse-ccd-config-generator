package uk.gov.hmcts.reform.fpl.enums;

import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

/**
 * Organisational access groups declared as enum constants. A role attaches to one of these via
 * {@link UserRole#getAccessGroup()}; the SDK then derives the AccessType + AccessTypeRole rows, plus
 * the group role's RoleToAccessProfiles row.
 *
 * <p>{@code caseAssignedRoleField} is the case role carried by the OrganisationPolicy's
 * {@code OrgPolicyCaseAssignedRole} — a role name, not a field name, despite the column's title. It
 * is resolved by a per-constant override rather than a constructor argument because it points back
 * into {@link UserRole}, which references this enum: a constructor argument would be read during a
 * circular static initialisation and come out null.</p>
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
      true,
      "PUBLICLAW:CARE_SUPERVISION_EPO:caseworker-approver-group:$ORGID$") {
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
  private final Boolean groupAccessEnabled;
  private final String caseAccessGroupIdTemplate;

  AccessGroups(String organisationProfileId, boolean accessMandatory, boolean accessDefault,
               boolean display, String description, String hintText, int displayOrder,
               Boolean groupAccessEnabled, String caseAccessGroupIdTemplate) {
    this.organisationProfileId = organisationProfileId;
    this.accessMandatory = accessMandatory;
    this.accessDefault = accessDefault;
    this.display = display;
    this.description = description;
    this.hintText = hintText;
    this.displayOrder = displayOrder;
    this.groupAccessEnabled = groupAccessEnabled;
    this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
  }

  @Override
  public String getAccessTypeId() {
    return name();
  }

  @Override
  public Boolean isGroupAccessEnabled() {
    return groupAccessEnabled;
  }
}
