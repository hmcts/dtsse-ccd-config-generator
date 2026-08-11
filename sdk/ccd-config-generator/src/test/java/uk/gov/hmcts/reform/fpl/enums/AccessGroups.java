package uk.gov.hmcts.reform.fpl.enums;

import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;

/**
 * Organisational access groups declared as enum constants. A role attaches to these via
 * {@link UserRole#getAccessGroups()}; the SDK then derives the AccessType + AccessTypeRole rows, plus
 * that role's RoleToAccessProfiles row.
 *
 * <p>The two constants here share an {@code accessTypeId} and differ only by organisation profile,
 * covering the case the definition store treats as two distinct access types — its
 * {@code AccessTypesValidator} keys on {@code (caseType, jurisdiction, accessTypeId,
 * organisationProfileId)} — and which earlier collapsed to a single generated row.</p>
 *
 * <p>Every member is a constructor argument: nothing here refers back to {@link UserRole}, so the two
 * enums are no longer circularly initialised and no member needs a lazily-resolving method body.
 * {@code caseAssignedRoleField} is the case role carried by the OrganisationPolicy's
 * {@code OrgPolicyCaseAssignedRole} — a role name, not a field name, despite the column's title —
 * given as a literal rather than {@code UserRole.CCD_SOLICITOR.getRole()}, which would read a null
 * constant mid-initialisation.</p>
 */
@Getter
public enum AccessGroups implements CCDAccessGroup {

  SOLICITOR_ORG_POLICY(
      "org-policy-access",
      "SOLICITOR_PROFILE",
      true,
      true,
      true,
      "Solicitor access type description",
      "Solicitor access type hint",
      1,
      "[SOLICITOR]",
      true,
      "PUBLICLAW:CARE_SUPERVISION_EPO:caseworker-approver:$ORGID$"),

  LOCAL_AUTHORITY_ORG_POLICY(
      "org-policy-access",
      "LOCALAUTH_PROFILE",
      true,
      true,
      true,
      "Local authority access type description",
      "Local authority access type hint",
      2,
      "[SOLICITOR]",
      true,
      "PUBLICLAW:CARE_SUPERVISION_EPO:caseworker-approver:$ORGID$");

  private final String accessTypeId;
  private final String organisationProfileId;
  private final boolean accessMandatory;
  private final boolean accessDefault;
  private final boolean display;
  private final String description;
  private final String hintText;
  private final int displayOrder;
  private final String caseAssignedRoleField;
  private final boolean groupAccessEnabled;
  private final String caseAccessGroupIdTemplate;

  AccessGroups(String accessTypeId, String organisationProfileId, boolean accessMandatory,
               boolean accessDefault, boolean display, String description, String hintText,
               int displayOrder, String caseAssignedRoleField, boolean groupAccessEnabled,
               String caseAccessGroupIdTemplate) {
    this.accessTypeId = accessTypeId;
    this.organisationProfileId = organisationProfileId;
    this.accessMandatory = accessMandatory;
    this.accessDefault = accessDefault;
    this.display = display;
    this.description = description;
    this.hintText = hintText;
    this.displayOrder = displayOrder;
    this.caseAssignedRoleField = caseAssignedRoleField;
    this.groupAccessEnabled = groupAccessEnabled;
    this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
  }
}
