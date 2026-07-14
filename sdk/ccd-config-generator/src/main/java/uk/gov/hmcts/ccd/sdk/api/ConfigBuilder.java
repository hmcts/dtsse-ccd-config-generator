package uk.gov.hmcts.ccd.sdk.api;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import uk.gov.hmcts.ccd.sdk.EventTypeBuilderImpl;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile.CaseRoleToAccessProfileBuilder;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange.NoticeOfChangeBuilder;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;

public interface ConfigBuilder<T, S, R extends HasRole> {

  EventTypeBuilder<T, R, S> event(String id);

  EventTypeBuilderImpl<T, R, S> attachScannedDocEvent();

  EventTypeBuilderImpl<T, R, S> handleSupplementaryEvent();

  void caseType(String caseType, String name, String description);

  /**
   * Defines a case type including optional CCD import metadata.
   */
  default void caseType(CaseType caseType) {
    caseType(caseType.getId(), caseType.getName(), caseType.getDescription());
  }

  void jurisdiction(String id, String name, String description);

  /**
   * Defines a jurisdiction including optional CCD import metadata.
   */
  default void jurisdiction(Jurisdiction jurisdiction) {
    jurisdiction(jurisdiction.getId(), jurisdiction.getName(), jurisdiction.getDescription());
  }

  /**
   * Omits the generator's default {@code LiveFrom} value from definition rows. Case-type and
   * jurisdiction metadata retain their explicitly configured live dates.
   */
  default void omitDefaultLiveFrom() {
    // Default no-op for backwards compatibility; implementations may override.
  }

  /**
   * Omits the conventional case-history field and automatically generated history tab.
   */
  default void omitCaseHistory() {
    // Default no-op for backwards compatibility; implementations may override.
  }

  /**
   * Sets the label of the conventional generated case-history field.
   */
  default void caseHistoryLabel(String label) {
    throw new UnsupportedOperationException("This ConfigBuilder does not support case-history metadata");
  }

  /**
   * Registers an ordered fixed list independently of the Java type used by a case-data field.
   *
   * @param id external CCD fixed-list identifier
   * @param values ordered enum values supplying the external codes and labels
   */
  default <E extends Enum<E>> void registerFixedList(String id, E... values) {
    throw new UnsupportedOperationException("This ConfigBuilder does not support fixed-list registration");
  }

  /** Registers generated complex schema types that are referenced through explicit CCD names. */
  default void registerComplexTypes(Class<?>... types) {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support explicit complex-type registration");
  }

  /**
   * Selects the typed schema projection used for this case type.
   */
  default void schemaProfile(Class<?> profile) {
    throw new UnsupportedOperationException("This ConfigBuilder does not support schema profiles");
  }

  /**
   * Selects the role constants applicable to this case type.
   */
  default void applicableRoles(R... roles) {
    throw new UnsupportedOperationException("This ConfigBuilder does not support applicable roles");
  }

  /**
   * Uses the legacy {@code CaseTypeId} column on case type, field and event authorisation rows.
   */
  default void legacyCaseAuthorisationIdColumn() {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support legacy authorisation columns");
  }

  /**
   * Retains LiveFrom on selected case-type authorisation rows.
   */
  default void retainCaseTypeAuthorisationLiveFrom(R... roles) {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support authorisation LiveFrom metadata");
  }

  /**
   * Keeps roles available to field, event and state policies without emitting a case-type
   * authorisation row for them.
   */
  default void omitCaseTypeAuthorisation(R... roles) {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support case-type authorisation exclusions");
  }

  /** Retains row-specific live dates on generated case-role rows. */
  default void retainCaseRoleLiveFrom() {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support case-role LiveFrom metadata");
  }

  /** Uses only explicit field access policies, without event, tab or search inference. */
  default void omitFieldAuthorisationInference() {
    throw new UnsupportedOperationException(
        "This ConfigBuilder does not support explicit-only field authorisation");
  }

  default void stateLabel(String stateId, String label) {
    // Implementations that expose resolved config metadata may override this.
  }

  void shutterService();

  void shutterService(R... roles);

  /**
   * Exclude roles from a shutter so they retain their normal case-type permissions rather than
   * being set to DELETE. The exclusion applies whether the shutter was requested for the whole
   * service via {@link #shutterService()} or for individual roles via {@link #shutterService(R...)},
   * and takes precedence over both.
   *
   * <p>Typically used to keep {@code caseworker-wa-task-configuration} out of a shutter, as
   * dropping that role to DELETE can cause issues for Work Allocation / Task Management.
   *
   * @param roles roles to exclude from the shutter
   */
  default void shutterServiceExclude(R... roles) {
    // Default no-op for backward compatibility; implementations may override.
  }

  void omitHistoryForRoles(R... roles);

  /**
   * Set AuthorisationCaseState explicitly.
   * Note that additional AuthorisationCaseState permissions are inferred based on grants of
   * event-level permissions.
   * @param state state
   * @param permissions permissions
   * @param role One or more roles
   */
  void grant(S state, Set<Permission> permissions, R... role);

  TabBuilder<T, R> tab(String tabId, String tabLabel);

  SearchBuilder<T, R> workBasketResultFields();

  SearchBuilder<T, R> workBasketInputFields();

  SearchBuilder<T, R> searchResultFields();

  SearchBuilder<T, R> searchInputFields();

  SearchCasesBuilder<T> searchCasesFields();

  void setCallbackHost(String s);

  /**
   * Sets the HMCTS service id supplementary value (`HMCTSServiceId`).
   */
  void hmctsServiceId(String value);

  void addPreEventHook(Function<Map<String, Object>, Map<String, Object>> hook);

  CaseRoleToAccessProfileBuilder<R> caseRoleToAccessProfile(R caseRole);

  CaseCategory.CaseCategoryBuilder categories(R caseRole);

  SearchCriteria.SearchCriteriaBuilder searchCriteria();

  SearchParty.SearchPartyBuilder searchParty();

  NoticeOfChangeBuilder<T, R> noticeOfChange();

  void grantComplexType(TypedPropertyGetter<T, ?> field, String listElementCode,
                        Set<Permission> permissions, R... roles);
}
