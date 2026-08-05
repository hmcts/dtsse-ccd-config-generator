package uk.gov.hmcts.ccd.sdk.api;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import uk.gov.hmcts.ccd.sdk.EventTypeBuilderImpl;
import uk.gov.hmcts.ccd.sdk.api.AccessType.AccessTypeBuilder;
import uk.gov.hmcts.ccd.sdk.api.AccessTypeRole.AccessTypeRoleBuilder;
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

  void jurisdiction(String id, String name, String description);

  default void stateLabel(String stateId, String label) {
    // Implementations that expose resolved config metadata may override this.
  }

  void shutterService();

  void shutterService(R... roles);

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

  /**
   * Declare a {@code RoleToAccessProfiles} mapping for a role that is <em>not</em> a member of the
   * case's role class — chiefly the group roles referenced by {@code AccessTypeRole.GroupRoleName}.
   *
   * <p>Such roles must not be added to the role class: that class is enum-iterated to emit
   * {@code AuthorisationCaseType} and {@code CaseRoles} rows, and group roles take part in neither.
   * This method emits only the {@code RoleToAccessProfiles} row and registers no role.</p>
   *
   * <p>Named distinctly from {@link #caseRoleToAccessProfile(HasRole)} because, with
   * {@code R extends HasRole}, the two would share an erasure and could not be overloads.</p>
   */
  CaseRoleToAccessProfileBuilder<R> roleToAccessProfile(HasRole role);

  CaseCategory.CaseCategoryBuilder categories(R caseRole);

  AccessTypeBuilder accessType(String accessTypeId);

  AccessTypeRoleBuilder accessTypeRole(String accessTypeId);

  SearchCriteria.SearchCriteriaBuilder searchCriteria();

  SearchParty.SearchPartyBuilder searchParty();

  NoticeOfChangeBuilder<T, R> noticeOfChange();

  void grantComplexType(TypedPropertyGetter<T, ?> field, String listElementCode,
                        Set<Permission> permissions, R... roles);
}
