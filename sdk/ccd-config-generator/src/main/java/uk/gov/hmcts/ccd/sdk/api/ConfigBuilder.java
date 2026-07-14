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

  void jurisdiction(String id, String name, String description);

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
   * Emit AuthorisationCaseState rows only for the grants declared explicitly via
   * {@link #grant(Object, Set, HasRole...)}. When set, no state permissions are inferred from
   * event grants, so the generated AuthorisationCaseState contains exactly the rows this case
   * type declares and nothing more.
   *
   * <p>By default (when this is not called) AuthorisationCaseState is broadened by deriving
   * permissions from every event's grants, which produces wider access than an explicit
   * {@code grant(state, ...)} row alone. Services migrating from hand-written JSON with
   * deliberately narrow state permissions can call this to opt out of that derivation.
   *
   * <p>This is a whole case-type switch and applies to every state. It does not affect field
   * authorisation; {@code Event.explicitGrants()} remains the switch for that.
   */
  default void explicitStateGrants() {
    // Default no-op for backward compatibility; implementations may override.
  }

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
