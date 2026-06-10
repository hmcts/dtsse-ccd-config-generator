package uk.gov.hmcts.ccd.sdk.api;

/**
 * Declares an organisational access type as an enum constant rather than via the
 * {@link ConfigBuilder#accessType} / {@link ConfigBuilder#accessTypeRole} builder calls.
 *
 * <p>A single constant carries the whole {@code AccessType} row plus the group-level portion of the
 * {@code AccessTypeRole} row. The per-role {@code OrganisationalRoleName} comes from the
 * {@link HasRole} that attaches to it via {@link HasRole#getAccessGroup()}, so one constant can be
 * shared by several roles.</p>
 *
 * <p>Generic over the case data type {@code T} so {@link #getCaseAssignedRoleField()} is a type-safe
 * method reference to a real case field (e.g. {@code CaseData::getOrganisationPolicy}) rather than a
 * free-text field id. The SDK resolves it to the CCD field name at build time.</p>
 *
 * @param <T> the case data type
 */
public interface CCDAccessGroup<T> {

  String getAccessTypeId();

  String getOrganisationProfileId();

  boolean isAccessMandatory();

  boolean isAccessDefault();

  boolean isDisplay();

  String getDescription();

  String getHintText();

  int getDisplayOrder();

  /**
   * The group role this access type maps to. A {@link HasRole} constant rather than a free-text
   * role name, so it cannot drift from the declared roles; the SDK reads its {@link HasRole#getRole()}
   * at build time.
   */
  HasRole getGroupRoleName();

  /**
   * Type-safe reference to the case field that holds the assigned organisation policy. Resolved to
   * the CCD field id at build time via the same property-name resolution Notice of Change uses.
   */
  TypedPropertyGetter<T, ?> getCaseAssignedRoleField();

  boolean isGroupAccessEnabled();

  String getCaseAccessGroupIdTemplate();

  default String getLiveTo() {
    return null;
  }
}
