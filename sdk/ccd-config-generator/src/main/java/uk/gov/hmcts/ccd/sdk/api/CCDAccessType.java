package uk.gov.hmcts.ccd.sdk.api;

/**
 * Spike: declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role {@code OrganisationalRoleName} comes from the
 * {@link HasRole} that attaches to it via {@link HasRole#getAccessType()}, so one access-type enum
 * constant can be shared by several roles.</p>
 */
public interface CCDAccessType {

  // --- AccessType row ---

  String getAccessTypeId();

  String getOrganisationProfileId();

  boolean isAccessMandatory();

  boolean isAccessDefault();

  boolean isDisplay();

  String getDescription();

  String getHintText();

  int getDisplayOrder();

  // --- group-level portion of the AccessTypeRole row (OrganisationalRoleName comes from the role) ---

  String getGroupRoleName();

  String getCaseAssignedRoleField();

  boolean isGroupAccessEnabled();

  String getCaseAccessGroupIdTemplate();

  default String getLiveTo() {
    return null;
  }
}
