package uk.gov.hmcts.reform.fpl.enums;

import java.util.List;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.reform.fpl.model.CaseData;

/**
 * Organisational access groups declared as enum constants, registered via
 * {@code builder.accessGroups(AccessGroups.values())}; the SDK then derives the AccessType +
 * AccessTypeRole rows, one pair per organisation profile.
 *
 * <p>{@code caseAssignedRoleField} is a type-safe method reference to a real {@link CaseData} field
 * ({@code CaseData::getOrganisationPolicy}); the SDK resolves it to the CCD field id at build time.</p>
 */
@Getter
public enum AccessGroups implements CCDAccessGroup<CaseData> {

  SOLICITOR_ORG_POLICY(
      List.of("SOLICITOR_PROFILE", "LOCALAUTH_PROFILE"),
      false,
      false,
      true,
      2,
      "Solicitor access type description",
      "Solicitor access type hint",
      UserRole.CASE_ACCESS_APPROVER,
      GroupRole.CASE_ACCESS_APPROVER_GROUP,
      CaseData::getOrganisationPolicy,
      true,
      "CARE_SUPERVISION_EPO:$ORGID$"),

  /** An access type granting only an organisational role: no group role, no group access. */
  APPROVER_CREATE_CASES(
      List.of("LOCALAUTH_PROFILE"),
      true,
      true,
      false,
      1,
      "Access to create cases",
      "Access to create cases",
      UserRole.CASE_ACCESS_APPROVER,
      null,
      null,
      false,
      null);

  private final List<String> organisationProfileIds;
  private final boolean accessMandatory;
  private final boolean accessDefault;
  private final boolean display;
  private final int displayOrder;
  private final String description;
  private final String hintText;
  private final HasRole organisationalRoleName;
  private final HasRole groupRoleName;
  private final TypedPropertyGetter<CaseData, ?> caseAssignedRoleField;
  private final boolean groupAccessEnabled;
  private final String caseAccessGroupIdTemplate;

  AccessGroups(List<String> organisationProfileIds, boolean accessMandatory, boolean accessDefault,
               boolean display, int displayOrder, String description, String hintText,
               HasRole organisationalRoleName, HasRole groupRoleName,
               TypedPropertyGetter<CaseData, ?> caseAssignedRoleField,
               boolean groupAccessEnabled, String caseAccessGroupIdTemplate) {
    this.organisationProfileIds = organisationProfileIds;
    this.accessMandatory = accessMandatory;
    this.accessDefault = accessDefault;
    this.display = display;
    this.displayOrder = displayOrder;
    this.description = description;
    this.hintText = hintText;
    this.organisationalRoleName = organisationalRoleName;
    this.groupRoleName = groupRoleName;
    this.caseAssignedRoleField = caseAssignedRoleField;
    this.groupAccessEnabled = groupAccessEnabled;
    this.caseAccessGroupIdTemplate = caseAccessGroupIdTemplate;
  }

  @Override
  public String getAccessTypeId() {
    return name();
  }
}
