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
import uk.gov.hmcts.ccd.sdk.api.callback.PostEventHook;

public interface ConfigBuilder<T, S, R extends HasRole> {

  EventTypeBuilder<T, R, S> event(String id);

  EventTypeBuilderImpl<T, R, S> attachScannedDocEvent();

  EventTypeBuilderImpl<T, R, S> handleSupplementaryEvent();

  void caseType(String caseType, String name, String description);

  void jurisdiction(String id, String name, String description);

  default void stateLabel(String stateId, String label) {
    // Implementations that expose resolved config metadata may override this.
  }

  /**
   * Sets the Jurisdiction sheet's {@code Shuttered} flag. This is a definition-time flag
   * persisted by {@code ccd-definition-store-api} and surfaced via its
   * {@code GET /internal/jurisdiction-ui-configs} endpoint; nothing in CCD or ExUI currently reads
   * it. It is unrelated to {@link #shutterService()}, which achieves an actual functional shutter
   * by restricting CRUD permissions.
   */
  void jurisdictionShuttered();

  /**
   * Sets the CaseType sheet's {@code EnableForDeletion} flag. This is a definition-time flag with
   * no known runtime consumer in CCD; {@code ccd-case-disposer} decides which case types are
   * eligible for permanent deletion from its own {@code DELETE_CASE_TYPES} deploy-time
   * configuration, not from this column.
   */
  void enableForDeletion();

  /**
   * Sets the CaseType sheet's {@code PrintableDocumentsUrl} column, the webhook the definition
   * store calls to obtain a printable representation of a case (consumed via
   * {@code CaseTypeEntity.getPrintWebhook()} at import time). Empty (the default) omits the
   * column.
   */
  void printableDocumentsUrl(String url);

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
   * Emit AuthorisationCaseState rows only for the grants declared explicitly, via
   * {@link #grant(Object, Set, HasRole...)} or {@code @CCD(access)} on a state constant. When set,
   * no state permissions are inferred from event grants.
   *
   * <p>By default (when this is not called) AuthorisationCaseState is broadened by deriving
   * permissions from every event's grants, which produces wider access than an explicit
   * {@code grant(state, ...)} row alone. Services migrating from hand-written JSON with
   * deliberately narrow state permissions can call this to opt out of that derivation.
   *
   * <p>This is a whole case-type switch and applies to every state. It does not affect field
   * authorisation; {@code Event.explicitGrants()} remains the switch for that.
   */
  void explicitStateGrants();

  /**
   * Stop a tab from adding read on a field that {@code @CCD(access)} authorises.
   *
   * <p>{@code AuthorisationCaseFieldGenerator} adds read for every field on a tab a role can see,
   * before it reads the field's {@code @CCD(access)}, so a field that names who may read it is
   * widened to every role on that tab and the declaration cannot take it back. Call this and such a
   * field takes no read from any tab, whether the tab declares {@code forRoles(...)} or not.
   *
   * <p>A field counts as authorised when {@code @CCD(access)} names an access class on the field
   * itself or on a {@code @JsonUnwrapped} parent it inherits from, which is how the grants
   * themselves are decided. So this is no help to a definition that relies on the tab injection to
   * grant read in the first place: ia-bail's 41 {@code progress_*} fields each want one role and
   * reach all of them, but neither they nor their parent names an access class, so calling this
   * changes nothing for them. Such a definition has to name the access first, and then this is what
   * stops it being widened again.
   *
   * <p>{@code caseHistory} counts as declared by {@code omitHistoryForRoles(...)}.
   *
   * <p>This stops tab read and nothing else. Permissions from the field's events still apply, as
   * does the read added for the workbasket and search fields a role can use, so a field granted
   * through either keeps it. A field from {@code TabBuilder.label(...)} is unaffected: it exists
   * only on its tab and has no other source of read.
   *
   * <p>This subtracts, and only the declaration puts anything back. A field whose access classes
   * name two of eight roles becomes readable by those two and no others, however many could read
   * it off the tab before — so a definition whose fields are authorised individually wants every
   * role it means to grant named on the field, not just the ones that edit it. The generator logs
   * the fields whose tab read it withheld, and warns about any left readable by nobody, so what a
   * case type gives up by calling this is visible at generation time.
   *
   * <p>Whether a field is covered turns on it naming an access class, not on what that class
   * grants, so a class granting a role write without read now emits {@code CU} where the tab used
   * to supply the {@code R}. CCD withholds unreadable fields from the case data it returns, so that
   * role opens the field blank whatever is stored, and submitting writes the blank back. The
   * generator warns about each such field and role, because the declaration is taken at face value
   * rather than being quietly widened; a role that is meant to write a field is meant to read it,
   * excepting a system role that only ever submits a value it supplies itself.
   */
  default void tabsRespectDeclaredFieldAccess() {
    // Defaulted rather than abstract so this stays source compatible for anything implementing
    // ConfigBuilder itself. Such an implementation discards the call, so a case type relying on
    // this has to be asserted against generated output rather than a stub builder.
  }

  /**
   * Suppress the {@code CaseHistory} tab the generator adds when no tab declares that ID.
   *
   * <p>By default {@code CaseTypeTabGenerator} prepends a tab {@code TabID=CaseHistory} placing the
   * {@code caseHistory} field, for backwards compatibility with case types that never declared one.
   * The check is on the tab's ID, so a case type that shows case history from tabs of its own — sscs
   * places {@code caseHistory} on a per-role {@code eventHistory_<role>} tab — still gets the
   * injected tab, and the service renders two History tabs where the hand-written definition had
   * one. Call this to opt out; the {@code caseHistory} {@code CaseField} row and its authorisations
   * are unaffected, so the field remains available to whichever tab does place it.
   *
   * <p>Only the injected tab is suppressed: a case type that declares {@code TabID=CaseHistory}
   * itself is emitted as declared whether or not this is called.
   */
  void noCaseHistoryTab();

  /**
   * Emit the {@code JurisdictionID} column on generated {@code CaseRoles} rows. By default the
   * column is omitted; call this to opt in when a definition needs the jurisdiction stamped on each
   * case role.
   *
   * <p>The jurisdiction is taken from {@link #jurisdiction(String, String, String)}. The importer's
   * {@code CaseRoleParser} reads only {@code ID}/{@code Name}/{@code Description}, so the column is
   * additive: it is tolerated when present and optional when absent.
   */
  void emitCaseRoleJurisdiction();

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

  /**
   * Register a hook that runs after every about-to-submit handler of this case type and sees its
   * response. Hooks run in the order they are registered, each receiving the response the previous
   * one returned; the last hook's response is what CCD receives.
   *
   * <p>On a case type that is not decentralised, a hook runs only for events with an about-to-submit
   * callback the SDK serves, since CCD makes no about-to-submit callback for an event that declares
   * none. On a decentralised case type CCD sends every submission to the SDK, and a hook runs for
   * each one made through {@code event(...)}, with or without an about-to-submit callback. For an
   * event without one, the hook receives a response carrying the event's case data and no state; the
   * case details carry the state the event ends in. That includes {@code DocumentUpdated}, the
   * system event CCD raises when a case document's metadata changes through CDAM, so a hook that
   * refuses submissions should let it through. Hooks do not run for events built with
   * {@code decentralisedEvent(...)} or {@code externalEvent(...)}, whose submit handlers return a
   * different response type.
   *
   * <p>A hook does not run for about-to-start, mid-event or submitted callbacks. It runs whether or
   * not the handler's response carries errors, so a hook that should leave a rejected submission
   * alone has to check for them. A handler that returns no response reaches the first hook as an
   * empty one, and a hook must not return null.
   *
   * <p>To refuse a submission, return the response with errors set; CCD shows them and the event is
   * not saved. Unlike a hook registered with {@link #addPreEventHook(Function)}, whose exceptions are
   * logged and ignored, an exception thrown by a post-event hook propagates and fails the callback
   * with a server error. That also stops the submission, but on a decentralised case type it leaves
   * behind the case pointer CCD created for a new case, because ccd-data-store-api only rolls a
   * pointer back on a client error. Keep throwing for failures the hook cannot report.
   */
  default void addPostEventHook(PostEventHook<T, S> hook) {
    // Defaulted rather than abstract so this stays source compatible for anything implementing
    // ConfigBuilder itself. It throws rather than discarding the hook, since a hook may enforce a
    // check, such as the caller's role, that must not be silently lost.
    throw new UnsupportedOperationException(getClass().getName()
        + " does not support post-event hooks; register them through the SDK's own ConfigBuilder");
  }

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

  AccessTypeBuilder accessType(String accessTypeId);

  AccessTypeRoleBuilder accessTypeRole(String accessTypeId);

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
