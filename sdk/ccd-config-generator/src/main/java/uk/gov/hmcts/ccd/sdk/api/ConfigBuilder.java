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
   * Emit the {@code JurisdictionID} column on generated {@code CaseRoles} rows. By default the
   * column is omitted so that output is byte-identical to before this option existed; call this to
   * opt in when a definition needs the jurisdiction stamped on each case role.
   *
   * <p>The jurisdiction is taken from {@link #jurisdiction(String, String, String)}. The importer's
   * {@code CaseRoleParser} reads only {@code ID}/{@code Name}/{@code Description}, so the column is
   * additive: it is tolerated when present and its absence is the historic default.
   */
  default void emitCaseRoleJurisdiction() {
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

  /**
   * Declare a {@code RoleToAccessProfiles} mapping keyed on a plain role-name string rather than a
   * {@link HasRole} constant. This carries the same fluent options as
   * {@link #caseRoleToAccessProfile(HasRole)} (access profiles, authorisations, case-access
   * categories, read-only, disabled, legacy IDAM role).
   *
   * <p>Real case-type definitions map many organisational / IDAM role names
   * (e.g. {@code caseworker-ia-system}) that are <em>not</em> case-type {@code UserRole}s. Those
   * cannot be declared via the typed API without first adding them to the {@code UserRole} enum,
   * which would register them and emit an {@code AuthorisationCaseType} row. This overload takes the
   * name verbatim: it emits only the {@code RoleToAccessProfiles} row and does <strong>not</strong>
   * register a {@code UserRole} or produce an {@code AuthorisationCaseType} row.
   *
   * @param roleName the literal role name to map, emitted verbatim as {@code RoleName}
   */
  CaseRoleToAccessProfileBuilder<R> roleToAccessProfile(String roleName);

  CaseCategory.CaseCategoryBuilder categories(R caseRole);

  SearchCriteria.SearchCriteriaBuilder searchCriteria();

  SearchParty.SearchPartyBuilder searchParty();

  NoticeOfChangeBuilder<T, R> noticeOfChange();

  void grantComplexType(TypedPropertyGetter<T, ?> field, String listElementCode,
                        Set<Permission> permissions, R... roles);

  /**
   * Sets the jurisdiction-wide service notice banner shown by XUI. The importer allows exactly
   * one banner per jurisdiction; calling this more than once overwrites the previous value
   * rather than adding a row.
   *
   * @param enabled whether the banner is shown
   * @param description the banner message
   * @param url an optional link target; pass {@code null} or {@code ""} if the banner carries no link
   * @param urlText the link text shown for {@code url}; pass {@code null} or {@code ""} if unused
   */
  void banner(boolean enabled, String description, String url, String urlText);
}
