package uk.gov.hmcts.reform.fpl.enums;

import uk.gov.hmcts.ccd.sdk.api.CCDAccessType;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.reform.fpl.model.CaseData;

/**
 * Spike: organisational access types declared as enum constants. A role attaches to one of these
 * via {@link UserRole#getAccessType()}; the SDK then derives the AccessType + AccessTypeRole rows.
 *
 * <p>{@code caseAssignedRoleField} is a type-safe method reference to a real {@link CaseData} field
 * ({@code CaseData::getOrganisationPolicy}); the SDK resolves it to the CCD field id at build time.</p>
 */
public enum AccessGroups implements CCDAccessType<CaseData> {

  SOLICITOR_ORG_POLICY(
      "SOLICITOR_PROFILE",
      true,
      true,
      true,
      "Solicitor access type description",
      "Solicitor access type hint",
      1,
      "caseworker-approver-group",
      CaseData::getOrganisationPolicy,
      true,
      "CARE_SUPERVISION_EPO:$ORGID$");

  private final String organisationProfileId;
  private final boolean accessMandatory;
  private final boolean accessDefault;
  private final boolean display;
  private final String description;
  private final String hintText;
  private final int displayOrder;
  private final String groupRoleName;
  private final TypedPropertyGetter<CaseData, ?> caseAssignedRoleField;
  private final boolean groupAccessEnabled;
  private final String caseAccessGroupIdTemplate;

  AccessGroups(String organisationProfileId, boolean accessMandatory, boolean accessDefault,
               boolean display, String description, String hintText, int displayOrder,
               String groupRoleName, TypedPropertyGetter<CaseData, ?> caseAssignedRoleField,
               boolean groupAccessEnabled, String caseAccessGroupIdTemplate) {
    this.organisationProfileId = organisationProfileId;
    this.accessMandatory = accessMandatory;
    this.accessDefault = accessDefault;
    this.display = display;
    this.description = description;
    this.hintText = hintText;
    this.displayOrder = displayOrder;
    this.groupRoleName = groupRoleName;
    this.caseAssignedRoleField = caseAssignedRoleField;
    this.groupAccessEnabled = groupAccessEnabled;
    this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
  }

  @Override
  public String getAccessTypeId() {
    return name();
  }

  @Override
  public String getOrganisationProfileId() {
    return organisationProfileId;
  }

  @Override
  public boolean isAccessMandatory() {
    return accessMandatory;
  }

  @Override
  public boolean isAccessDefault() {
    return accessDefault;
  }

  @Override
  public boolean isDisplay() {
    return display;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getHintText() {
    return hintText;
  }

  @Override
  public int getDisplayOrder() {
    return displayOrder;
  }

  @Override
  public String getGroupRoleName() {
    return groupRoleName;
  }

  @Override
  public TypedPropertyGetter<CaseData, ?> getCaseAssignedRoleField() {
    return caseAssignedRoleField;
  }

  @Override
  public boolean isGroupAccessEnabled() {
    return groupAccessEnabled;
  }

  @Override
  public String getCaseAccessGroupIdTemplate() {
    return caseAccessGroupIdTemplate;
  }
}
