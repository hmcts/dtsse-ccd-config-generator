package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.DefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.ir.AuthorisationRows;
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.BannerModel;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeAuthModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.EventModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.PageModel;
import uk.gov.hmcts.ccd.sdk.converter.model.PassthroughSheet;
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.SearchFieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.StateModel;
import uk.gov.hmcts.ccd.sdk.converter.model.TabModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Default {@link DefinitionLinker}: selects one case type from the IR and builds its semantic
 * model — states, roles, fixed lists, complex types, fields (with Java type mapping), events,
 * wizard pages, tabs, search sheets, derived access classes and passthrough sheets — routing
 * everything that has no config-generator equivalent to a gap entry.
 */
public class DefaultDefinitionLinker implements DefinitionLinker {

  private static final String CASE_ROLE_PATTERN = "^\\[.+\\]$";

  @Override
  public CaseTypeModel link(DefinitionIr ir, ConversionOptions options, GapCollector gaps) {
    String caseTypeId = selectCaseType(ir, options);
    SheetRow caseType = requireCaseTypeRow(ir, caseTypeId);
    final SheetRow jurisdiction = jurisdictionRow(ir, caseType);

    // An ID can appear on BOTH the ComplexTypes and FixedLists sheets (e.g. ia's
    // appealGroundsEuRefusal — a complex type whose single MultiSelectList member reuses the
    // list of the same name). A field's bare FieldType reference to such an ID means the complex
    // type, and the SDK would generate two Java classes of the same name (a complex-type class
    // and an enum) that collide. The complex type wins; the colliding FixedList is generated no
    // enum and passed through instead, exactly as for an orphan or illegal-identifier list.
    final Set<String> complexTypeIds = complexTypeIds(ir, caseTypeId);

    // Companion class/enum names are PascalCased (finding #3/#4) and allocated collision-free
    // against this shared set, seeded with the fixed generated type names so a list/type ID that
    // PascalCases to one of them is deterministically suffixed rather than clashing. The set is
    // shared across the fixed-list and complex-type passes so no FL enum and complex type collide.
    Set<String> usedTypeNames = new LinkedHashSet<>();
    usedTypeNames.add("CaseData");
    usedTypeNames.add("State");
    usedTypeNames.add("UserRole");

    // In retrofit mode a generated companion must also avoid colliding with an existing model type of
    // the same PascalCase name — but only with the OTHER kind, because a companion that matches its
    // own kind binds to it and is never emitted (see RetrofitComplexTypeEmitter / the FixedList
    // rebind). So the fixed-list pass reserves existing CLASS names and the complex-type pass reserves
    // existing ENUM names; reserving the matching kind would wrongly suffix a reference to a
    // never-emitted type. The CCD wire ID always round-trips via @ComplexType(name).
    Map<String, String> fixedListEnumNames = new LinkedHashMap<>();
    List<PassthroughSheet> fixedListPassthrough = new ArrayList<>();
    if (options.getRetrofitReservedFixedListNames() != null) {
      usedTypeNames.addAll(options.getRetrofitReservedFixedListNames());
    }
    final List<FixedListModel> fixedLists =
        buildFixedLists(ir, caseTypeId, options, gaps, fixedListEnumNames, fixedListPassthrough,
            complexTypeIds, usedTypeNames);
    // Drop the reserved class names again before the complex-type pass: a complex type DOES bind to
    // an existing class of the same name (no companion), so keeping them reserved would suffix its
    // shared reference. The complex-type pass instead reserves existing enum names. Names actually
    // allocated to fixed-list enums above stay in the set (they are no longer plain reservations).
    if (options.getRetrofitReservedFixedListNames() != null) {
      Set<String> allocatedFixedListNames = new LinkedHashSet<>(fixedListEnumNames.values());
      options.getRetrofitReservedFixedListNames().stream()
          .filter(name -> !allocatedFixedListNames.contains(name))
          .forEach(usedTypeNames::remove);
    }
    if (options.getRetrofitReservedComplexTypeNames() != null) {
      usedTypeNames.addAll(options.getRetrofitReservedComplexTypeNames());
    }

    Map<String, String> complexTypeRefs = new LinkedHashMap<>();
    List<PassthroughSheet> complexTypePassthrough = new ArrayList<>();
    List<ComplexTypeModel> complexTypes =
        buildComplexTypes(ir, caseTypeId, options, gaps, fixedListEnumNames, complexTypeRefs,
            complexTypePassthrough, usedTypeNames);

    TypeMapper.EnumResolver enumResolver = fixedListEnumNames::get;
    TypeMapper.ComplexResolver complexResolver = complexTypeRefs::get;

    List<FieldModel> caseFields =
        buildCaseFields(ir, caseTypeId, options, gaps, enumResolver, complexResolver);
    final List<StateModel> states = buildStates(ir, caseTypeId, gaps);
    List<EventModel> events = buildEvents(ir, caseTypeId, options, gaps);
    List<TabModel> tabs = buildTabs(ir, caseTypeId);

    List<SearchFieldModel> searchInput =
        buildSearchFields(ir, caseTypeId, SheetName.SEARCH_INPUT_FIELD, false);
    List<SearchFieldModel> searchResult =
        buildSearchFields(ir, caseTypeId, SheetName.SEARCH_RESULT_FIELD, false);
    List<SearchFieldModel> workBasketInput =
        buildSearchFields(ir, caseTypeId, SheetName.WORK_BASKET_INPUT_FIELD, false);
    List<SearchFieldModel> workBasketResult =
        buildSearchFields(ir, caseTypeId, SheetName.WORK_BASKET_RESULT_FIELDS, false);
    final List<SearchFieldModel> searchCasesResult =
        buildSearchFields(ir, caseTypeId, SheetName.SEARCH_CASES_RESULT_FIELDS, true);

    final List<RoleModel> roles = buildRoles(ir, caseTypeId, options, gaps);

    AccessDerivation access = deriveAccessClasses(
        ir, caseTypeId, caseFields, events, tabs,
        List.of(searchInput, searchResult, workBasketInput, workBasketResult), gaps);
    final List<FieldModel> caseFieldsWithAccess =
        attachAccessClasses(caseFields, access.fieldClassNames);

    // Fold repeated field families (applicant1*/applicant2*, …) into @JsonUnwrapped complex
    // types so CaseData stays within Java's field/constructor limits. Runs last, once every
    // field has its final id/javaName/access. Collision-avoidance uses the names already taken
    // by generated complex types, fixed-list enums and the State/UserRole enums.
    // Synthetic cluster types must avoid every already-allocated companion class/enum name. The
    // shared usedTypeNames set already holds the PascalCase complex-type and fixed-list class names
    // (plus CaseData/State/UserRole), so reuse it directly.
    Set<String> reservedTypeNames = new LinkedHashSet<>(usedTypeNames);
    // Retrofit mode annotates the team's EXISTING model, which already carries its own
    // @JsonUnwrapped structure; synthesising fresh cluster complex types would invent members the
    // model does not have. So clustering is skipped and the flat fields are kept as-is — the
    // retrofit binder later maps each unwrapped leaf to the model's real parent/member getter.
    FieldClusterer.Result clustered = options.isRetrofit()
        ? FieldClusterer.Result.unclustered(caseFieldsWithAccess)
        : new FieldClusterer(gaps).cluster(caseFieldsWithAccess, reservedTypeNames);
    List<ComplexTypeModel> allComplexTypes = new ArrayList<>(complexTypes);
    allComplexTypes.addAll(clustered.synthesizedTypes());

    // AuthorisationComplexType: generate builder.grantComplexType(...) for every row whose complex
    // field resolves to a plain CaseData member and whose role is a registered UserRole; any row
    // that does not (unresolvable field, unregistered role) stays a residual passthrough.
    Set<String> resolvableComplexAuthFields = resolvableComplexAuthFields(caseFieldsWithAccess,
        clustered.refs());
    Set<String> registeredRoleIds = new LinkedHashSet<>();
    roles.forEach(r -> registeredRoleIds.add(r.getId()));
    ComplexTypeAuthResult complexTypeAuth = linkComplexTypeAuthorisations(
        ir, options, gaps, resolvableComplexAuthFields, registeredRoleIds);
    final boolean emitCaseRoleJurisdiction = allCaseRolesCarryJurisdiction(ir, caseTypeId, gaps);

    List<PassthroughSheet> passthroughSheets = buildPassthroughSheets(ir, options, gaps);
    passthroughSheets.addAll(complexTypeAuth.passthrough());
    passthroughSheets.addAll(fixedListPassthrough);
    passthroughSheets.addAll(complexTypePassthrough);
    passthroughSheets.addAll(buildEventToComplexTypesPassthrough(ir, caseTypeId, options, gaps));
    passthroughSheets.addAll(buildEventFieldColumnPassthrough(ir, caseTypeId, events, options, gaps));
    // State Description (@CCD(description)), RoleToAccessProfiles unregistered roles
    // (roleToAccessProfile(String)), the search extras (per-field lambda), the CaseRoles
    // JurisdictionID (emitCaseRoleJurisdiction) and the CaseType/Jurisdiction EnableForDeletion/
    // Shuttered flags are now all emitted via real builder calls, so no passthrough is produced for
    // them here.
    passthroughSheets.addAll(buildCaseEventColumnPassthrough(ir, caseTypeId, events, options));
    // SearchCasesResultFields role/useCase/ListElementCode/ResultsOrdering/DisplayContextParameter
    // are now all emitted via the SearchCases per-field lambda (see CoreConfigEmitter), so the sheet
    // needs no passthrough and no FieldShowCondition graft — buildSearchFieldPassthrough is retired.
    // A genuinely-unknown FieldType (no Java carrier, not a real FieldType constant) is no longer
    // grafted back over the SDK's String→Text inference: it now fails as an OMITTED_FAIL gap (recorded
    // in buildFieldModel). When SOME but not all CaseRoles rows carry a JurisdictionID the
    // all-or-nothing generator switch cannot be used, and that mixed usage is likewise now an
    // OMITTED_FAIL gap (recorded in allCaseRolesCarryJurisdiction) rather than a per-row column graft.
    // When every row carries it the native emitCaseRoleJurisdiction() switch reproduces it.

    return CaseTypeModel.builder()
        .caseTypeId(caseTypeId)
        .caseTypeName(caseType.getString(Columns.NAME).orElse(caseTypeId))
        .caseTypeDescription(caseType.getString(Columns.DESCRIPTION).orElse(null))
        .banner(buildBanner(ir))
        .enableForDeletion(caseTypeFlag(caseType, Columns.ENABLE_FOR_DELETION))
        .jurisdictionShuttered(jurisdiction != null && rowFlag(jurisdiction, Columns.SHUTTERED))
        .printableDocumentsUrl(caseType.getString(Columns.PRINTABLE_DOCUMENTS_URL).orElse(null))
        .emitCaseRoleJurisdiction(emitCaseRoleJurisdiction)
        .complexTypeAuthorisations(complexTypeAuth.grants())
        .jurisdictionId(jurisdiction == null ? null : jurisdiction.getString(Columns.ID).orElse(null))
        .jurisdictionName(jurisdiction == null ? null : jurisdiction.getString(Columns.NAME).orElse(null))
        .jurisdictionDescription(
            jurisdiction == null ? null : jurisdiction.getString(Columns.DESCRIPTION).orElse(null))
        .states(states)
        .roles(roles)
        .caseFields(clustered.caseFields())
        .complexTypes(allComplexTypes)
        .fixedLists(fixedLists)
        .events(events)
        .tabs(tabs)
        .searchInputFields(searchInput)
        .searchResultFields(searchResult)
        .workBasketInputFields(workBasketInput)
        .workBasketResultFields(workBasketResult)
        .searchCasesResultFields(searchCasesResult)
        .stateAuthorisations(rowsFor(ir, SheetName.AUTHORISATION_CASE_STATE, caseTypeId))
        .accessClasses(access.accessClasses)
        .accessSummaryNote(access.summaryNote)
        .searchCriteria(rowsFor(ir, SheetName.SEARCH_CRITERIA, caseTypeId))
        .searchParties(rowsFor(ir, SheetName.SEARCH_PARTY, caseTypeId))
        .challengeQuestions(rowsFor(ir, SheetName.CHALLENGE_QUESTION, caseTypeId))
        .roleToAccessProfiles(rowsFor(ir, SheetName.ROLE_TO_ACCESS_PROFILES, caseTypeId))
        .categories(rowsFor(ir, SheetName.CATEGORY, caseTypeId))
        .passthroughSheets(passthroughSheets)
        .clusteredFieldRefs(clustered.refs())
        .build();
  }

  private String selectCaseType(DefinitionIr ir, ConversionOptions options) {
    List<SheetRow> caseTypes = ir.rows(SheetName.CASE_TYPE);
    if (options.getCaseTypeId() != null) {
      return options.getCaseTypeId();
    }
    List<String> ids = caseTypes.stream()
        .map(r -> r.getString(Columns.ID).orElse(null))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.size() == 1) {
      return ids.get(0);
    }
    if (ids.isEmpty()) {
      throw new IllegalArgumentException(
          "No CaseType rows found; cannot determine a case type to convert");
    }
    throw new IllegalArgumentException(
        "Multiple case types present (" + String.join(", ", ids)
            + "); specify one with --case-type");
  }

  private SheetRow requireCaseTypeRow(DefinitionIr ir, String caseTypeId) {
    return ir.rows(SheetName.CASE_TYPE).stream()
        .filter(r -> r.getString(Columns.ID).map(caseTypeId::equals).orElse(false))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "No CaseType row with ID '" + caseTypeId + "'"));
  }

  private SheetRow jurisdictionRow(DefinitionIr ir, SheetRow caseType) {
    Optional<String> jurisdictionId = caseType.getString("JurisdictionID");
    return ir.rows(SheetName.JURISDICTION).stream()
        .filter(r -> jurisdictionId
            .map(id -> r.getString(Columns.ID).map(id::equals).orElse(false))
            .orElse(true))
        .findFirst()
        .orElse(null);
  }

  private List<StateModel> buildStates(DefinitionIr ir, String caseTypeId, GapCollector gaps) {
    List<StateModel> states = new ArrayList<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.STATE, caseTypeId)) {
      Optional<String> id = row.getString(Columns.ID);
      if (id.isEmpty()) {
        continue;
      }
      String stateId = id.get();
      if (!IdentifierSanitiser.isLegalIdentifier(stateId)) {
        gaps.add(GapEntry.builder()
            .sheet("State")
            .rowKey(stateId)
            .column("ID")
            .value(stateId)
            .category(GapCategory.IDENTIFIER_SANITISED)
            .action(GapAction.OMITTED_FAIL)
            .detail("State IDs become enum constants looked up by toString(); '" + stateId
                + "' is not a legal Java identifier and cannot be represented")
            .build());
        continue;
      }
      states.add(StateModel.builder()
          .id(stateId)
          .name(row.getString(Columns.NAME).orElse(stateId))
          .titleDisplay(row.getString(Columns.TITLE_DISPLAY).orElse(null))
          // Read the Description verbatim (getDisplayText, untrimmed): a state Description is
          // user-facing prose that may carry meaningful trailing whitespace (ET), and it is
          // emitted onto @CCD(description) and compared exactly, so trimming it here would drop a
          // trailing space and diverge from the input.
          .description(row.getDisplayText(Columns.DESCRIPTION).orElse(null))
          .build());
    }
    return states;
  }

  /**
   * The CaseEvent-sheet columns grafted verbatim (additively) onto the generator's per-event
   * {@code CaseEvent/<id>.json} files, keyed by event ID. {@code SignificantEvent} is now emitted
   * via {@code EventBuilder.significant()} (see {@code EventsConfigEmitter}), so it is no longer
   * grafted. Since the converter emits no callback wiring at all (see {@code EventsConfigEmitter}
   * and {@code CoreConfigEmitter}) the generator writes none of the {@code CallBackURL*}/
   * {@code RetriesTimeout*} columns either. So each is grafted back and
   * can never collide with a generated value: the original callback endpoints (env
   * {@code ${CCD_DEF_*}} placeholders included) are reproduced byte-for-byte and the migrated
   * service keeps serving them unchanged. Both about-to-start retry spellings are carried (real
   * definitions use either {@code RetriesTimeoutURLAboutToStartEvent} or the plain
   * {@code RetriesTimeoutAboutToStartEvent}). The CaseEvent-sheet {@code CallBackURLMidEvent} is
   * deliberately NOT grafted here: mid-event is a per-page {@code CaseEventToFields} property
   * (grafted there by {@code buildEventFieldColumnPassthrough}), and the vestigial CaseEvent copy
   * some definitions (sscs) carry is dropped on both sides by the {@code CASE_EVENT_MID_EVENT}
   * comparator rule.
   */
  private static final List<String> CASE_EVENT_ADDITIVE_GRAFT_COLUMNS = List.of(
      Columns.CALLBACK_URL_ABOUT_TO_START_EVENT,
      Columns.CALLBACK_URL_ABOUT_TO_SUBMIT_EVENT,
      Columns.CALLBACK_URL_SUBMITTED_EVENT,
      Columns.RETRIES_TIMEOUT_ABOUT_TO_START_EVENT,
      Columns.RETRIES_TIMEOUT_URL_ABOUT_TO_START_EVENT,
      Columns.RETRIES_TIMEOUT_URL_ABOUT_TO_SUBMIT_EVENT,
      Columns.RETRIES_TIMEOUT_URL_SUBMITTED_EVENT);

  /**
   * Builds the single per-event {@code CaseEvent/<id>.json} column-graft covering every CaseEvent
   * column the SDK's {@code EventBuilder} does not reproduce. One passthrough sheet per (overlay
   * suffix, event) — they MUST NOT be split across sheets that target the same file, because the
   * report writer writes (not merges) each sheet to its relative path, so two sheets on the same
   * {@code CaseEvent/<id>.json} would clobber one another.
   *
   * <p>This is now a purely <b>additive</b> graft: the callback URL / retry columns
   * ({@link #CASE_EVENT_ADDITIVE_GRAFT_COLUMNS}) are copied raw (JSON type and {@code ${CCD_DEF_*}}
   * placeholders preserved); the generator emits none of these, so the graft can never shadow a
   * generated value. A conditional or multi-target {@code PostConditionState} is no longer grafted:
   * the SDK's {@code EventBuilder} models a single post-state, so the generator emits only the
   * primary state and the maintainer accepts the collapse as a semantic difference (forgiven by the
   * {@code CONDITIONAL_POST_STATE} comparator rule — see {@link #parsePostState}).
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param events the linked events (only emitted events are reproduced)
   * @param options the conversion options (for overlay suffix resolution)
   * @return the CaseEvent column-passthrough sheets, one per (overlay, event), or empty
   */
  private List<PassthroughSheet> buildCaseEventColumnPassthrough(
      DefinitionIr ir, String caseTypeId, List<EventModel> events, ConversionOptions options) {
    Set<String> emittedEvents = new LinkedHashSet<>();
    for (EventModel event : events) {
      emittedEvents.add(event.getId());
    }
    Map<SuffixTarget, Map<String, Object>> byTarget = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_EVENT, caseTypeId)) {
      String eventId = row.getString(Columns.ID).orElse(null);
      if (eventId == null || !emittedEvents.contains(eventId)) {
        continue;
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put(Columns.ID, eventId);

      // A conditional/multi-target PostConditionState is no longer grafted back over the SDK's
      // primary-only value: the maintainer accepts the collapse to the single primary state as a
      // semantic difference (see parsePostState's gap and the CONDITIONAL_POST_STATE comparator
      // rule). The generator emits the primary state and the comparator forgives it.

      // Additive columns (SignificantEvent, callback URLs, retries): copy raw, guarding on the
      // string form only to skip blank/absent cells. Placeholders are preserved verbatim.
      for (String column : CASE_EVENT_ADDITIVE_GRAFT_COLUMNS) {
        if (row.getString(column).filter(v -> !v.isBlank()).isPresent()) {
          out.put(column, row.getColumns().get(column));
        }
      }

      // Only the ID survived → the input set none of the grafted columns; nothing to merge.
      if (out.size() <= 1) {
        continue;
      }
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      byTarget.put(new SuffixTarget(suffix, eventId), out);
    }
    List<PassthroughSheet> sheets = new ArrayList<>();
    for (Map.Entry<SuffixTarget, Map<String, Object>> entry : byTarget.entrySet()) {
      String suffix = entry.getKey().suffix();
      String eventId = entry.getKey().target();
      sheets.add(PassthroughSheet.builder()
          .relativePath("CaseEvent/" + eventId + ".json")
          .primaryKeys(List.of(Columns.ID))
          .overlaySuffix(suffix)
          .overlayCondition(OverlayResolver.conditionFor(suffix, options))
          .rows(List.of(entry.getValue()))
          .build());
    }
    return sheets;
  }

  private List<RoleModel> buildRoles(DefinitionIr ir, String caseTypeId, ConversionOptions options, GapCollector gaps) {
    Set<String> roleIds = new LinkedHashSet<>();
    collectRoles(ir, SheetName.AUTHORISATION_CASE_TYPE, caseTypeId, roleIds);
    collectRoles(ir, SheetName.AUTHORISATION_CASE_FIELD, caseTypeId, roleIds);
    collectRoles(ir, SheetName.AUTHORISATION_CASE_EVENT, caseTypeId, roleIds);
    collectRoles(ir, SheetName.AUTHORISATION_CASE_STATE, caseTypeId, roleIds);

    Map<String, SheetRow> caseRoleRows = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_ROLE, caseTypeId)) {
      row.getString(Columns.ID).ifPresent(id -> {
        roleIds.add(id);
        caseRoleRows.put(id, row);
      });
    }

    Map<String, String> caseTypePerms = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.AUTHORISATION_CASE_TYPE, caseTypeId)) {
      // Case-type grants are carried on the UserRole enum's static caseTypePermissions, which the
      // SDK cannot vary per environment. Where a definition splits them across mutually-exclusive
      // overlay fragments (fpl's -shuttered grants D, -nonshuttered grants CRU to the same roles),
      // only the fragment active for the current build may contribute — otherwise the two collide
      // last-wins and the enum carries the wrong grant. A base row (no configured suffix) always
      // contributes; a suffixed row contributes only when its overlay predicate is active.
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      if (suffix != null) {
        OverlayCondition condition = OverlayResolver.conditionFor(suffix, options);
        if (condition != null && !condition.isActive()) {
          continue;
        }
      }
      // Expand the flat/UserRoles/AccessControl shapes so array-encoded case-type grants (fpl
      // grants CRUD=D and CRUD=CRU to whole role sets via UserRoles arrays) reach every role.
      caseTypePerms.putAll(AuthorisationRows.roleGrants(row));
    }

    Set<String> usedConstants = new LinkedHashSet<>();
    List<RoleModel> roles = new ArrayList<>();
    for (String id : roleIds) {
      boolean caseRole = id.matches(CASE_ROLE_PATTERN);
      String constant = uniqueConstant(id, usedConstants, gaps);
      SheetRow caseRoleRow = caseRoleRows.get(id);
      roles.add(RoleModel.builder()
          .id(id)
          .javaConstant(constant)
          .caseTypePermissions(caseTypePerms.getOrDefault(id, ""))
          .caseRole(caseRole)
          .name(caseRoleRow == null ? null : caseRoleRow.getString(Columns.NAME).orElse(null))
          .description(
              caseRoleRow == null ? null : caseRoleRow.getString(Columns.DESCRIPTION).orElse(null))
          .build());
    }
    return roles;
  }

  private void collectRoles(
      DefinitionIr ir, SheetName sheet, String caseTypeId, Set<String> into) {
    for (SheetRow row : ir.rowsForCaseType(sheet, caseTypeId)) {
      // A row may name one flat role or many via the UserRoles/AccessControl array shorthands;
      // register each so it becomes a UserRole enum constant the grant derivation can reference.
      into.addAll(AuthorisationRows.roleGrants(row).keySet());
    }
  }

  private String uniqueConstant(String id, Set<String> used, GapCollector gaps) {
    String bare = id.matches(CASE_ROLE_PATTERN) ? id.substring(1, id.length() - 1) : id;
    String constant = IdentifierSanitiser.toConstantName(bare);
    if (!bare.equals(constant)) {
      gaps.add(GapEntry.builder()
          .sheet("Roles")
          .rowKey(id)
          .column(null)
          .value(id)
          .category(GapCategory.IDENTIFIER_SANITISED)
          .action(GapAction.CONDITIONAL_CODE)
          .detail("Role '" + id + "' becomes enum constant " + constant
              + "; the exact role string is preserved via getRole()")
          .build());
    }
    String candidate = constant;
    int suffix = 2;
    while (used.contains(candidate)) {
      candidate = constant + "_" + suffix++;
    }
    used.add(candidate);
    return candidate;
  }

  /**
   * Builds fixed lists, grouping rows by ID across every sheet fragment that contributed one
   * (see {@link DefinitionIr#rowsForCaseType}: FixedLists rows carry no CaseTypeID in the
   * canonical schema, so a shared {@code common/} layout legitimately re-reads the same list
   * from several input directories). An exact repeat of an (ID, ListElementCode, ListElement)
   * triple is that legitimate re-read and is deduplicated silently; a repeat of (ID,
   * ListElementCode) with a different label means two distinct case types' lists collided
   * under the same ID, which is ambiguous input that must be caught here rather than silently
   * merged.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param gaps the gap collector for identifier sanitisation notices
   * @param enumNames output map of fixed list ID to generated enum name
   * @return the fixed list models, one per distinct ID
   */
  private List<FixedListModel> buildFixedLists(
      DefinitionIr ir, String caseTypeId, ConversionOptions options, GapCollector gaps,
      Map<String, String> enumNames, List<PassthroughSheet> passthrough,
      Set<String> complexTypeIds, Set<String> usedTypeNames) {
    Map<String, List<SheetRow>> byId = groupById(ir.rowsForCaseType(SheetName.FIXED_LISTS, caseTypeId));
    Set<String> referenced = referencedTypeParameters(ir, caseTypeId);
    List<FixedListModel> lists = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byId.entrySet()) {
      String id = entry.getKey();
      if (complexTypeIds.contains(id)) {
        // The ID is also a complex type; generating an enum here would collide with the generated
        // complex-type class of the same name. Skip the enum and pass the list rows through so the
        // FixedLists sheet is still reproduced; any MultiSelectList/FixedList member referencing
        // this ID falls back (via TypeMapper) to a String carrier with typeParameterOverride=<id>,
        // preserving the field's FieldType/FieldTypeParameter exactly.
        passthroughFixedList(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("FixedLists")
            .rowKey(id)
            .column(Columns.ID)
            .value(id)
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("FixedList ID '" + id + "' is also a ComplexType ID; the complex-type class"
                + " takes the name, so no enum is generated and the list rows are passed through")
            .build());
        continue;
      }
      if (!referenced.contains(id)) {
        // A FixedList that no CaseField or ComplexType member references is not in the SDK
        // generator's type set (config.getTypes()), so FixedListGenerator never emits an enum
        // file for it. Reproduce the orphan list via passthrough instead of a generated enum that
        // would have no counterpart on the generated side.
        passthroughFixedList(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("FixedLists")
            .rowKey(id)
            .column(Columns.ID)
            .value(id)
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("FixedList '" + id + "' is not referenced by any field, so the SDK generates"
                + " no enum for it; its rows are passed through as raw JSON")
            .build());
        continue;
      }
      if (!IdentifierSanitiser.isLegalIdentifier(id)) {
        // The SDK's FixedListGenerator names the enum, its output file and every referencing
        // field's FieldTypeParameter after the enum's simple class name, so a list whose ID is
        // not a legal Java identifier (e.g. fpl's 'Stoke-on-TrentDFJCourts') cannot round-trip
        // through a generated enum. Skip enum generation — TypeMapper.fixedList then falls back
        // to String with typeOverride=FixedList and typeParameterOverride=<id>, preserving the
        // field's FieldType/FieldTypeParameter — and pass the FixedLists rows through verbatim
        // so the list definition itself is reproduced.
        passthroughFixedList(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("FixedLists")
            .rowKey(id)
            .column(Columns.ID)
            .value(id)
            .category(GapCategory.IDENTIFIER_SANITISED)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("FixedList ID '" + id + "' is not a legal Java identifier and cannot name a"
                + " generated enum; the list rows are passed through as raw JSON and referencing"
                + " fields keep the ID via typeParameterOverride")
            .build());
        continue;
      }
      // PascalCase the enum's Java name (dropping any machine FL_ prefix) and record it as the type
      // every referencing field resolves to; the wire ID stays `id` and round-trips via
      // @ComplexType(name = id) (finding #4). enumNames maps ID -> Java enum name for TypeMapper.
      String enumClassName = TypeClassNamer.allocate(TypeClassNamer.fixedListName(id), usedTypeNames);
      enumNames.put(id, enumClassName);
      Set<String> usedConstants = new LinkedHashSet<>();
      List<FixedListModel.Item> items = new ArrayList<>();
      Map<String, String> labelsByCode = new LinkedHashMap<>();
      Map<String, java.nio.file.Path> sourceByCode = new LinkedHashMap<>();
      for (SheetRow row : entry.getValue()) {
        String code = row.getString(Columns.LIST_ELEMENT_CODE).orElse("");
        String label = row.getDisplayText(Columns.LIST_ELEMENT).orElse(code);
        String existingLabel = labelsByCode.putIfAbsent(code, label);
        sourceByCode.putIfAbsent(code, row.getSource());
        if (existingLabel != null) {
          if (existingLabel.equals(label)) {
            continue;
          }
          if (!java.util.Objects.equals(sourceByCode.get(code), row.getSource())) {
            // The same code with different labels arriving from DIFFERENT files points at two
            // unrelated case types' lists colliding under one ID — ambiguous input.
            throw new IllegalArgumentException(
                "FixedList '" + id + "' has conflicting labels for ListElementCode '" + code
                    + "': '" + existingLabel + "' vs '" + label + "'; split the conflicting "
                    + "input directories or add a CaseTypeID column to disambiguate");
          }
          // A duplicate code with a different label INSIDE one file is a genuine data quirk
          // (e.g. ia's govUkNationalities lists 'CC' twice). The definition store imports both
          // rows, so both items are emitted — the generator then reproduces the input exactly.
          gaps.add(GapEntry.builder()
              .sheet("FixedLists")
              .rowKey(id + "/" + code)
              .column(Columns.LIST_ELEMENT_CODE)
              .value(code)
              .category(GapCategory.IDENTIFIER_SANITISED)
              .action(GapAction.CONDITIONAL_CODE)
              .detail("Duplicate ListElementCode '" + code + "' with differing labels ('"
                  + existingLabel + "' vs '" + label + "') in the same file; both entries are "
                  + "emitted as separate constants, reproducing the input")
              .build());
        }
        String constant = IdentifierSanitiser.toConstantName(code);
        if (!code.equals(constant)) {
          gaps.add(GapEntry.builder()
              .sheet("FixedLists")
              .rowKey(id + "/" + code)
              .column("ListElementCode")
              .value(code)
              .category(GapCategory.IDENTIFIER_SANITISED)
              .action(GapAction.CONDITIONAL_CODE)
              .detail("List item code '" + code + "' becomes enum constant " + constant
                  + "; the exact code is preserved via @JsonProperty")
              .build());
        }
        String unique = constant;
        int suffix = 2;
        while (usedConstants.contains(unique)) {
          unique = constant + "_" + suffix++;
        }
        usedConstants.add(unique);
        items.add(FixedListModel.Item.builder()
            .code(code)
            .label(row.getDisplayText(Columns.LIST_ELEMENT).orElse(code))
            .javaConstant(unique)
            .displayOrder(row.getInteger(Columns.DISPLAY_ORDER).orElse(null))
            .build());
      }
      lists.add(FixedListModel.builder()
          .id(id)
          .javaClassName(enumNames.get(id))
          .items(items)
          .build());
    }
    return lists;
  }

  /**
   * The set of complex-type IDs declared for a case type. Used to detect IDs that appear on both
   * the ComplexTypes and FixedLists sheets, where the complex-type class must win the shared name.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @return the complex-type IDs
   */
  private Set<String> complexTypeIds(DefinitionIr ir, String caseTypeId) {
    Set<String> ids = new LinkedHashSet<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.COMPLEX_TYPES, caseTypeId)) {
      row.getString(Columns.ID).ifPresent(ids::add);
    }
    return ids;
  }

  /**
   * The set of type names any CaseField or ComplexType member references, via either the
   * FieldTypeParameter (the usual FixedList/Complex reference) or the FieldType itself (some
   * definitions name a type directly as a field's FieldType). Used to decide which FixedLists the
   * SDK will actually generate an enum for.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @return the referenced type-parameter/type names
   */
  private Set<String> referencedTypeParameters(DefinitionIr ir, String caseTypeId) {
    Set<String> referenced = new LinkedHashSet<>();
    // Every CaseField reference counts. But a reference from a ComplexTypes member counts only when
    // that complex type is itself reachable from a CaseData field: an unreachable complex type is
    // passed through as raw JSON and never registered in the SDK's type set, so a FixedList it
    // alone references (fpl's fl_Annex, reached only through the SharedStorage-only AnnexType) would
    // get a generated enum with no counterpart FixedLists rows on the generated side. Restricting
    // the ComplexTypes scan to reachable types makes such an orphan-path FixedList fall to the
    // unreferenced branch (passthrough), matching what the generator actually emits.
    Map<String, List<SheetRow>> complexById =
        groupById(ir.rowsForCaseType(SheetName.COMPLEX_TYPES, caseTypeId));
    Set<String> reachableComplex = reachableComplexTypes(ir, caseTypeId, complexById);
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId)) {
      row.getString(Columns.FIELD_TYPE_PARAMETER).ifPresent(referenced::add);
      row.getString(Columns.FIELD_TYPE).ifPresent(referenced::add);
    }
    for (Map.Entry<String, List<SheetRow>> entry : complexById.entrySet()) {
      if (!reachableComplex.contains(entry.getKey())) {
        continue;
      }
      for (SheetRow row : entry.getValue()) {
        row.getString(Columns.FIELD_TYPE_PARAMETER).ifPresent(referenced::add);
        row.getString(Columns.FIELD_TYPE).ifPresent(referenced::add);
      }
    }
    return referenced;
  }

  /**
   * Routes a FixedList that cannot be represented as a generated enum through the passthrough
   * machinery, one manifest entry per overlay suffix so the rows land in
   * {@code FixedLists/<id>.json} verbatim (matching where the SDK's FixedListGenerator would
   * write them). Rows are keyed by ListElementCode for the merge.
   *
   * @param id the FixedList ID
   * @param rows the sheet rows for the list
   * @param options the conversion options (supplies overlay predicates)
   * @param passthrough the passthrough collector to append to
   */
  private void passthroughFixedList(
      String id, List<SheetRow> rows, ConversionOptions options,
      List<PassthroughSheet> passthrough) {
    Map<String, List<SheetRow>> bySuffix = new LinkedHashMap<>();
    for (SheetRow row : rows) {
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      bySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(row);
    }
    for (Map.Entry<String, List<SheetRow>> entry : bySuffix.entrySet()) {
      String suffix = entry.getKey();
      List<Map<String, Object>> raw = new ArrayList<>();
      for (SheetRow row : entry.getValue()) {
        raw.add(new LinkedHashMap<>(row.getColumns()));
      }
      passthrough.add(PassthroughSheet.builder()
          .relativePath("FixedLists/" + id + ".json")
          .primaryKeys(List.of(Columns.LIST_ELEMENT_CODE))
          .overlaySuffix(suffix)
          .overlayCondition(OverlayResolver.conditionFor(suffix, options))
          .rows(raw)
          .build());
    }
  }

  /**
   * Builds complex types, grouping rows by ID across every sheet fragment that contributed
   * one. As with {@link #buildFixedLists}, ComplexTypes rows carry no CaseTypeID in the
   * canonical schema, so distinct case types' complex types read from the same directory are
   * merged by ID; a repeated (ID, ListElementCode) member with a different FieldType or
   * ElementLabel signals two unrelated types collided under the same ID, which is caught here
   * rather than silently merged. An exact repeat of a member is the legitimate shared-layout
   * re-read and is deduplicated silently.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param gaps the gap collector for identifier sanitisation and predefined-type notices
   * @param enumNames fixed list ID to generated enum name, for member type resolution
   * @param complexTypeRefs output map of complex type ID to its Java type reference
   * @return the complex type models, one per distinct non-predefined ID
   */
  private List<ComplexTypeModel> buildComplexTypes(
      DefinitionIr ir,
      String caseTypeId,
      ConversionOptions options,
      GapCollector gaps,
      Map<String, String> enumNames,
      Map<String, String> complexTypeRefs,
      List<PassthroughSheet> passthrough,
      Set<String> usedTypeNames) {
    Map<String, List<SheetRow>> byId =
        groupById(ir.rowsForCaseType(SheetName.COMPLEX_TYPES, caseTypeId));

    // The SDK's ConfigResolver only generates a ComplexTypes class for a type that some CaseData
    // field (directly or transitively) declares. A complex type declared on the sheet but never
    // referenced (ia's 'caseFlag', 'appointment') yields no generated class, so its rows are
    // reproduced via passthrough rather than a phantom generated type.
    Set<String> reachable = reachableComplexTypes(ir, caseTypeId, byId);

    // Populate the resolver before building members (a complex type's member may reference a
    // sibling complex type). A predefined type maps to its SDK FQN; a generated type maps to a
    // PascalCase Java class name (finding #3) allocated collision-free, while the wire ID stays the
    // sheet ID and round-trips via @ComplexType(name = id). Only types that will actually yield a
    // generated class (non-predefined, reachable, legal identifier) are allocated a name; the rest
    // are passed through below and dropped from the resolver, so no name is wasted.
    for (String id : byId.keySet()) {
      if (SdkPredefinedTypes.isPredefined(id)) {
        complexTypeRefs.put(id, SdkPredefinedTypes.javaTypeFor(id));
      } else if (reachable.contains(id) && IdentifierSanitiser.isLegalIdentifier(id)) {
        complexTypeRefs.put(id, TypeClassNamer.allocate(
            TypeClassNamer.complexTypeName(id), usedTypeNames));
      } else {
        // Unreachable or illegal-identifier: passed through below; a temporary raw-ID mapping keeps
        // any member resolver lookup non-null until the type is removed from the map.
        complexTypeRefs.put(id, id);
      }
    }

    Map<String, Integer> depths = computeDepths(byId);
    TypeMapper.EnumResolver enumResolver = enumNames::get;
    TypeMapper.ComplexResolver complexResolver = complexTypeRefs::get;

    List<ComplexTypeModel> models = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byId.entrySet()) {
      String id = entry.getKey();
      if (!SdkPredefinedTypes.isPredefined(id) && !reachable.contains(id)) {
        // Unreferenced complex type: the SDK generates no class for it. Preserve its rows via
        // passthrough and drop it from complexTypeRefs so nothing resolves to a phantom type.
        complexTypeRefs.remove(id);
        passthroughComplexType(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("ComplexTypes")
            .rowKey(id)
            .column("ID")
            .value(id)
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("Complex type '" + id + "' is not referenced by any field, so the SDK"
                + " generates no class for it; its rows are passed through as raw JSON")
            .build());
        continue;
      }
      if (SdkPredefinedTypes.isPredefined(id)) {
        // The SDK ships this type with @ComplexType(generate = false), so ComplexTypeGenerator
        // emits NO ComplexTypes rows for it — the definition store learns the type from the SDK
        // jar at runtime. But a definition that explicitly re-declares the type's members on its
        // ComplexTypes sheet (fpl's Fee) imports those rows, so reproduce them verbatim via
        // passthrough; the referencing field still resolves to the built-in class (complexTypeRefs
        // above), so no class is generated and only the sheet rows are grafted back.
        passthroughComplexType(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("ComplexTypes")
            .rowKey(id)
            .column("ID")
            .value(id)
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("Complex type '" + id + "' is an SDK-predefined type; referencing fields map"
                + " to the built-in class and no ComplexTypes class is generated, so the input's"
                + " explicit ComplexTypes rows are passed through as raw JSON")
            .build());
        continue;
      }
      if (!IdentifierSanitiser.isLegalIdentifier(id)) {
        // The complex-type ID is not a legal Java identifier (prl's 'schoolDirections&Details').
        // The SDK derives the emitted CCD type ID from the generated class name, so sanitising the
        // ID would change the round-tripped data; and a class named with the raw ID does not
        // compile. Route the whole type through passthrough (as for an unreferenced type) — the
        // referencing field falls back to a String carrier keeping its original FieldType via the
        // unknown-type path, and the ComplexTypes rows are reproduced verbatim.
        complexTypeRefs.remove(id);
        passthroughComplexType(id, entry.getValue(), options, passthrough);
        gaps.add(GapEntry.builder()
            .sheet("ComplexTypes")
            .rowKey(id)
            .column("ID")
            .value(id)
            .category(GapCategory.IDENTIFIER_SANITISED)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("Complex type '" + id + "' is not a legal Java identifier, so no class can be"
                + " generated without changing the emitted type ID; its rows are passed through as"
                + " raw JSON and referencing fields carry the original FieldType")
            .build());
        continue;
      }
      List<FieldModel> members = new ArrayList<>();
      Set<String> usedNames = new LinkedHashSet<>();
      Map<String, String> seenTypes = new LinkedHashMap<>();
      for (SheetRow row : entry.getValue()) {
        String code = row.getString(Columns.LIST_ELEMENT_CODE).orElse("");
        String fieldType = row.getString(Columns.FIELD_TYPE).orElse("Text");
        String elementLabel = row.getDisplayText(Columns.ELEMENT_LABEL).orElse(null);
        String existingType = seenTypes.putIfAbsent(code, fieldType);
        if (existingType != null) {
          if (!existingType.equals(fieldType)) {
            // Unlike a FixedList (where two rows sharing a code map to two enum constants), a
            // complex type member becomes a single Java field. Two rows with the same code but a
            // different FieldType cannot map to one field — whether from one file or two colliding
            // case types — so this is flagged rather than merged.
            throw new IllegalArgumentException(
                "ComplexType '" + id + "' has conflicting FieldType for ListElementCode '"
                    + code + "' (" + existingType + " vs " + fieldType + "); a complex-type "
                    + "member maps to one Java field and cannot represent both. Split the "
                    + "conflicting input directories or add a CaseTypeID column to disambiguate");
          }
          // Same FieldType, differing only in display metadata (e.g. an ElementLabel that leads
          // with a '*' marker in one fragment): the Java field is identical. A definition that
          // ships both a flat ComplexTypes.json and a ComplexTypes/ fragment directory (prl) can
          // legitimately carry a member twice; the xlsx build merges them last-wins. Keep the
          // first-seen member and record the duplicate as a gap rather than crashing.
          gaps.add(GapEntry.builder()
              .sheet("ComplexTypes")
              .rowKey(id + "|" + code)
              .column("ElementLabel")
              .value(elementLabel)
              .category(GapCategory.UNSUPPORTED_VALUE)
              .action(GapAction.PASSTHROUGH_COLUMN)
              .detail("ComplexType '" + id + "' member '" + code + "' is declared more than once"
                  + " with the same FieldType but differing display metadata; keeping the"
                  + " first-seen definition (the Java field is identical)")
              .build());
          continue;
        }
        members.add(buildFieldModel(
            row,
            code,
            elementLabel,
            usedNames,
            gaps,
            "ComplexTypes",
            // Complex-type members are not env-gated as CaseData members here: the SDK gate applies
            // to a CaseData field (or unwrapped member), and a per-member gate on a shared complex
            // type would need the same @CCD(gate) attribute on the generated complex class member.
            // Overlay-only complex-type members keep their existing passthrough routing (null gate).
            null,
            enumResolver,
            complexResolver));
      }
      models.add(ComplexTypeModel.builder()
          .id(id)
          .javaClassName(complexTypeRefs.get(id))
          .members(members)
          .depth(depths.getOrDefault(id, 0))
          .build());
    }
    return models;
  }

  /**
   * The set of complex-type IDs reachable from a CaseData field. Seeds from every CaseField
   * FieldType/FieldTypeParameter naming a complex type, then transitively follows each reachable
   * type's members' FieldType/FieldTypeParameter, so nested complex types are kept. Predefined
   * types are treated as always reachable (they map to built-in classes, never passthrough).
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param byId the complex-type rows grouped by ID
   * @return the reachable complex-type IDs
   */
  private Set<String> reachableComplexTypes(
      DefinitionIr ir, String caseTypeId, Map<String, List<SheetRow>> byId) {
    Set<String> reachable = new LinkedHashSet<>();
    java.util.Deque<String> queue = new java.util.ArrayDeque<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId)) {
      row.getString(Columns.FIELD_TYPE).filter(byId::containsKey).ifPresent(queue::add);
      row.getString(Columns.FIELD_TYPE_PARAMETER).filter(byId::containsKey).ifPresent(queue::add);
    }
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!reachable.add(id)) {
        continue;
      }
      for (SheetRow row : byId.getOrDefault(id, List.of())) {
        row.getString(Columns.FIELD_TYPE).filter(byId::containsKey).ifPresent(queue::add);
        row.getString(Columns.FIELD_TYPE_PARAMETER).filter(byId::containsKey).ifPresent(queue::add);
      }
    }
    return reachable;
  }

  private void passthroughComplexType(
      String id, List<SheetRow> rows, ConversionOptions options,
      List<PassthroughSheet> passthrough) {
    Map<String, List<SheetRow>> bySuffix = new LinkedHashMap<>();
    for (SheetRow row : rows) {
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      bySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(row);
    }
    for (Map.Entry<String, List<SheetRow>> entry : bySuffix.entrySet()) {
      String suffix = entry.getKey();
      List<Map<String, Object>> raw = new ArrayList<>();
      for (SheetRow row : entry.getValue()) {
        raw.add(new LinkedHashMap<>(row.getColumns()));
      }
      passthrough.add(PassthroughSheet.builder()
          .relativePath("ComplexTypes/" + id + ".json")
          .primaryKeys(List.of(Columns.ID, Columns.LIST_ELEMENT_CODE))
          .overlaySuffix(suffix)
          .overlayCondition(OverlayResolver.conditionFor(suffix, options))
          .rows(raw)
          .build());
    }
  }

  private Map<String, Integer> computeDepths(Map<String, List<SheetRow>> byId) {
    Map<String, Set<String>> references = new LinkedHashMap<>();
    for (Map.Entry<String, List<SheetRow>> entry : byId.entrySet()) {
      Set<String> refs = new LinkedHashSet<>();
      for (SheetRow row : entry.getValue()) {
        row.getString(Columns.FIELD_TYPE_PARAMETER)
            .filter(byId::containsKey)
            .ifPresent(refs::add);
      }
      references.put(entry.getKey(), refs);
    }
    Map<String, Integer> depths = new LinkedHashMap<>();
    for (String id : byId.keySet()) {
      depths.put(id, depth(id, references, new LinkedHashSet<>()));
    }
    return depths;
  }

  private int depth(String id, Map<String, Set<String>> references, Set<String> visiting) {
    if (!visiting.add(id)) {
      return 0;
    }
    int max = 0;
    for (String ref : references.getOrDefault(id, Set.of())) {
      max = Math.max(max, 1 + depth(ref, references, visiting));
    }
    visiting.remove(id);
    return max;
  }

  private List<FieldModel> buildCaseFields(
      DefinitionIr ir,
      String caseTypeId,
      ConversionOptions options,
      GapCollector gaps,
      TypeMapper.EnumResolver enumResolver,
      TypeMapper.ComplexResolver complexResolver) {
    List<FieldModel> fields = new ArrayList<>();
    Set<String> usedNames = new LinkedHashSet<>();
    List<SheetRow> rows = ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId);
    // Ids that have a base (non-overlay) row: the base row is the ungated member for that id, so a
    // complementary overlay variant of the same id is never emitted as a second, gated member.
    Set<String> baseIds = new LinkedHashSet<>();
    for (SheetRow row : rows) {
      if (row.isBase()) {
        row.getString(Columns.ID).ifPresent(baseIds::add);
      }
    }
    Set<String> emittedGatedIds = new LinkedHashSet<>();
    for (SheetRow row : rows) {
      Optional<String> id = row.getString(Columns.ID);
      if (id.isEmpty()) {
        continue;
      }
      // An overlay-only CaseField whose suffix has a configured predicate becomes a real CaseData
      // member gated by @CCD(gate) rather than passthrough (see gateFor). Complementary fragments
      // (e.g. civil's -JO-prod / -JO-jb-nonprod carry the same field IDs) would otherwise emit one
      // member per fragment; the inactive-suffix variant is skipped at convert time so the id is
      // emitted once, gated on the active suffix's predicate. A base row (or an overlay row with no
      // configured predicate) is unaffected: gateFor returns null and the id emits as today.
      String gate = gateFor(row, options);
      boolean gated = gate != null;
      if (gated) {
        String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
        OverlayCondition condition = OverlayResolver.conditionFor(suffix, options);
        // Skip the variant whose predicate is inactive in the convert-time environment, any id that
        // already has a base (ungated) member, and any duplicate gated id — so each gated field
        // yields exactly one member, gated on the active suffix's predicate.
        if (condition == null || !condition.isActive()
            || baseIds.contains(id.get()) || !emittedGatedIds.add(id.get())) {
          continue;
        }
      }
      fields.add(buildFieldModel(
          row,
          id.get(),
          row.getDisplayText(Columns.LABEL).orElse(null),
          usedNames,
          gaps,
          "CaseField",
          gate,
          enumResolver,
          complexResolver));
    }
    return fields;
  }

  /**
   * The {@code @CCD(gate)} expression for an overlay-only CaseField row, or null when the field is
   * not gated. A row is gated when it carries an overlay suffix that has a configured
   * {@link OverlayCondition}: the field lives only in that environment's definition, with no base
   * row, so instead of dropping it to per-suffix passthrough the converter emits it as a real
   * CaseData member the SDK includes only when the gate matches. Returns null for a base row, or an
   * overlay row whose suffix has no configured predicate (which keeps its passthrough routing).
   *
   * @param row the CaseField row
   * @param options the conversion options (supplies overlay predicates)
   * @return the gate expression, or null when ungated
   */
  private String gateFor(SheetRow row, ConversionOptions options) {
    if (row.isBase()) {
      return null;
    }
    String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
    OverlayCondition condition = OverlayResolver.conditionFor(suffix, options);
    return condition == null ? null : condition.toExpression();
  }

  private FieldModel buildFieldModel(
      SheetRow row,
      String id,
      String label,
      Set<String> usedNames,
      GapCollector gaps,
      String sheet,
      String gate,
      TypeMapper.EnumResolver enumResolver,
      TypeMapper.ComplexResolver complexResolver) {
    // A CaseData member must be a resolvable JavaBean property: the SDK's PropertyUtils resolves a
    // CaseData::getX reference (used by every tab/event/search placement) by decapitalising the
    // getter name via java.beans.Introspector.decapitalize and then findField-ing that name. A
    // field whose id starts with a single upper-case letter (e.g. LinkedCasesComponentLauncher)
    // would be stored verbatim, so findField("linkedCasesComponentLauncher") misses it, the
    // @JsonProperty is never read, and the placement row's CaseFieldID comes out lower-cased —
    // diverging from the CaseField ID (which FieldUtils.getFieldId derives correctly). Naming the
    // member with the bean-decapitalised form makes the getter resolvable, and the @JsonProperty
    // the emitter adds (because javaName != id) restores the exact CCD id on both paths.
    String memberName = beanDecapitalise(IdentifierSanitiser.toMemberName(id));
    if (!id.equals(memberName)) {
      gaps.add(GapEntry.builder()
          .sheet(sheet)
          .rowKey(id)
          .column("ID")
          .value(id)
          .category(GapCategory.IDENTIFIER_SANITISED)
          .action(GapAction.CONDITIONAL_CODE)
          .detail("Field ID '" + id + "' becomes member " + memberName
              + "; the exact ID is preserved via @JsonProperty")
          .build());
    }
    String unique = memberName;
    int suffix = 2;
    while (usedNames.contains(unique)) {
      unique = memberName + suffix++;
    }
    usedNames.add(unique);

    String fieldType = row.getString(Columns.FIELD_TYPE).orElse("Text");
    String fieldTypeParameter = row.getString(Columns.FIELD_TYPE_PARAMETER).orElse(null);
    TypeMapper.Mapping mapping =
        TypeMapper.map(fieldType, fieldTypeParameter, enumResolver, complexResolver);

    if (mapping.isUnknownType()) {
      gaps.add(GapEntry.builder()
          .sheet(sheet)
          .rowKey(id)
          .column(Columns.FIELD_TYPE)
          .value(fieldType)
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.OMITTED_FAIL)
          .detail("FieldType '" + fieldType + "' is not an SDK FieldType constant, a generated "
              + "complex type, or a predefined type, so the field can only be generated as String "
              + "(FieldType=Text) — the original type is not supported. Conversion fails unless "
              + "--allow-gaps is set (which keeps the String inference). Add the type as an SDK "
              + "FieldType constant or model it as a complex type to convert it faithfully.")
          .build());
    }

    return FieldModel.builder()
        .id(id)
        .javaName(unique)
        .fieldType(fieldType)
        .fieldTypeParameter(fieldTypeParameter)
        .javaType(mapping.getJavaType())
        .typeOverride(mapping.getTypeOverride())
        .typeParameterOverride(mapping.getTypeParameterOverride())
        .unknownType(mapping.isUnknownType())
        .label(label)
        .hint(row.getDisplayText(Columns.HINT_TEXT).orElse(null))
        .showCondition(row.getString(Columns.FIELD_SHOW_CONDITION).orElse(null))
        .regex(row.getString(Columns.REGULAR_EXPRESSION).orElse(null))
        .categoryId(row.getString(Columns.CATEGORY_ID).orElse(null))
        .searchable(row.getYesNo(Columns.SEARCHABLE).orElse(null))
        .retainHiddenValue(row.getYesNo(Columns.RETAIN_HIDDEN_VALUE).orElse(null))
        .min(row.getInteger(Columns.MIN).orElse(null))
        .max(row.getInteger(Columns.MAX).orElse(null))
        .accessClassNames(List.of())
        // A gated field is a real CaseData member (emitted with @CCD(gate)), so clear its overlay
        // tags: CaseDataEmitter emits it like any other member and every downstream path (event
        // placement, access-class attachment) treats it normally. An ungated overlay row keeps its
        // tags so it still routes to passthrough.
        .overlayTags(gate != null ? new LinkedHashSet<>() : new LinkedHashSet<>(row.getOverlayTags()))
        .gate(gate)
        .build();
  }

  private List<EventModel> buildEvents(
      DefinitionIr ir, String caseTypeId, ConversionOptions options, GapCollector gaps) {
    List<SheetRow> eventRows = ir.rowsForCaseType(SheetName.CASE_EVENT, caseTypeId);
    Map<String, List<SheetRow>> fieldRowsByEvent =
        groupBy(ir.rowsForCaseType(SheetName.CASE_EVENT_TO_FIELDS, caseTypeId), Columns.CASE_EVENT_ID);
    Map<String, List<SheetRow>> complexRowsByEvent =
        groupBy(ir.rowsForCaseType(SheetName.CASE_EVENT_TO_COMPLEX_TYPES, caseTypeId),
            Columns.CASE_EVENT_ID);
    Map<String, Map<String, String>> grantsByEvent = eventGrants(ir, caseTypeId);

    Set<String> usedNames = new LinkedHashSet<>();
    List<EventModel> events = new ArrayList<>();
    for (SheetRow row : eventRows) {
      Optional<String> id = row.getString(Columns.ID);
      if (id.isEmpty()) {
        continue;
      }
      String eventId = id.get();
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      OverlayCondition condition = OverlayResolver.conditionFor(suffix, options);
      if (!row.isBase() && suffix == null) {
        gaps.add(GapEntry.builder()
            .sheet("CaseEvent")
            .rowKey(eventId)
            .column(null)
            .value(String.join(",", row.getOverlayTags()))
            .category(GapCategory.OVERLAY_NOT_EXPRESSIBLE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("Event '" + eventId + "' comes from overlay " + row.getOverlayTags()
                + " with no configured predicate; passed through as raw JSON")
            .build());
      }
      String javaName = uniqueEventName(eventId, suffix, usedNames);
      events.add(EventModel.builder()
          .id(eventId)
          .javaName(javaName)
          .name(row.getString(Columns.NAME).orElse(eventId))
          .description(row.getString(Columns.DESCRIPTION).orElse(null))
          .preStates(parsePreStates(row.getString(Columns.PRE_CONDITION_STATES).orElse("")))
          .postState(parsePostState(
              row.getString(Columns.POST_CONDITION_STATE).orElse(null), eventId, gaps))
          .displayOrder(row.getInteger(Columns.DISPLAY_ORDER).orElse(null))
          .showCondition(row.getString(Columns.EVENT_ENABLING_CONDITION).orElse(null))
          .showSummary(row.getYesNo(Columns.SHOW_SUMMARY).orElse(null))
          .showEventNotes(row.getYesNo(Columns.SHOW_EVENT_NOTES).orElse(null))
          // SignificantEvent is a genuinely boolean column (civil ships JSON true/false, ia/ET ship
          // "Yes"); getYesNo canonicalises both. Emitted via EventBuilder.significant().
          .significant(row.getYesNo(Columns.SIGNIFICANT_EVENT).orElse(null))
          // CanSaveDraft is a genuinely boolean CaseEvent column; getYesNo canonicalises Y/true.
          // Emitted via EventBuilder.canSaveDraft() (create events only).
          .canSaveDraft(row.getYesNo(Columns.CAN_SAVE_DRAFT).orElse(null))
          .endButtonLabel(row.getDisplayText(Columns.END_BUTTON_LABEL).orElse(null))
          .publish(row.getYesNo(Columns.PUBLISH).orElse(null))
          .publishAs(row.getString(Columns.PUBLISH_AS).orElse(null))
          .ttlIncrement(row.getInteger(Columns.TTL_INCREMENT).orElse(null))
          .grants(grantsByEvent.getOrDefault(eventId, Map.of()))
          .pages(buildPages(
              fieldRowsByEvent.getOrDefault(eventId, List.of()),
              complexRowsByEvent.getOrDefault(eventId, List.of())))
          .overlayCondition(condition)
          .overlaySuffix(suffix)
          .build());
    }
    return events;
  }

  /**
   * Decapitalises a name to its canonical JavaBean property form, matching
   * {@link java.beans.Introspector#decapitalize} — the transform the SDK's PropertyUtils applies
   * when resolving a {@code CaseData::getX} getter reference back to a field name. A single leading
   * upper-case letter is lower-cased ({@code LinkedCases} → {@code linkedCases}); a name beginning
   * with two or more upper-case letters is left untouched (JavaBean rule for acronyms, e.g.
   * {@code URL} stays {@code URL}) so the getter still resolves. Names already starting lower-case
   * are returned unchanged.
   *
   * @param name the sanitised member-name candidate
   * @return the bean-decapitalised member name
   */
  private static String beanDecapitalise(String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private String uniqueEventName(String eventId, String suffix, Set<String> used) {
    String base = IdentifierSanitiser.toMemberName(eventId);
    if (suffix != null) {
      base = base + "_" + IdentifierSanitiser.toMemberName(suffix);
    }
    String candidate = base;
    int n = 2;
    while (used.contains(candidate)) {
      candidate = base + n++;
    }
    used.add(candidate);
    return candidate;
  }

  private Map<String, Map<String, String>> eventGrants(DefinitionIr ir, String caseTypeId) {
    Map<String, Map<String, String>> grants = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.AUTHORISATION_CASE_EVENT, caseTypeId)) {
      Optional<String> eventId = row.getString(Columns.CASE_EVENT_ID);
      if (eventId.isEmpty()) {
        continue;
      }
      // Same three shapes as AuthorisationCaseField (flat, UserRoles array, AccessControl array);
      // expand to per-role grants (access-control-transformer.js).
      grants.computeIfAbsent(eventId.get(), k -> new LinkedHashMap<>())
          .putAll(AuthorisationRows.roleGrants(row));
    }
    return grants;
  }

  private List<String> parsePreStates(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    if ("*".equals(raw.trim())) {
      return List.of("*");
    }
    List<String> states = new ArrayList<>();
    for (String part : raw.split(";")) {
      String stateName = stripStateDecorations(part);
      if (!stateName.isEmpty()) {
        states.add(stateName);
      }
    }
    return states;
  }

  /**
   * Extracts the bare state ID from a CCD pre/post state token. CCD state columns encode an
   * optional show condition in parentheses and an optional {@code :priority} suffix, e.g.
   * {@code appealStartedByAdmin(isAdmin="Yes"):2}. Only the state ID is a legal Java enum
   * reference; the condition and priority are dropped here (they are display/ordering metadata,
   * preserved by the round-trip through column-level passthrough of the State column).
   *
   * @param token one {@code ;}-separated state token
   * @return the bare state ID, trimmed
   */
  private String stripStateDecorations(String token) {
    String trimmed = token.trim();
    int paren = trimmed.indexOf('(');
    if (paren >= 0) {
      trimmed = trimmed.substring(0, paren).trim();
    } else {
      int colon = trimmed.indexOf(':');
      if (colon >= 0) {
        trimmed = trimmed.substring(0, colon).trim();
      }
    }
    return trimmed;
  }

  /**
   * Parses the post-condition state, which shares the pre-state token grammar and may list
   * several conditional alternatives (e.g. {@code stateA(cond):2;stateB}). The SDK models a
   * single post-state per event, so the first token's state ID is used as the post-state and any
   * conditional alternatives are dropped — a maintainer-accepted semantic difference. The regenerated
   * definition transitions only to the primary state; the data store's runtime first-match-wins
   * evaluation of the conditional alternatives (CasePostStateService) is lost, so a team relying on
   * it must reimplement the transition in an aboutToSubmit callback. The loss is recorded as a
   * {@code CONDITIONAL_CODE} gap (the migrating team must add the callback) and forgiven on the
   * round-trip by the {@code CONDITIONAL_POST_STATE} comparator rule.
   *
   * @param raw the raw PostConditionState value
   * @param eventId the owning event, for gap reporting
   * @param gaps the gap collector
   * @return the primary post-state ID, or null when absent or {@code *}
   */
  private String parsePostState(String raw, String eventId, GapCollector gaps) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    if ("*".equals(raw.trim())) {
      return "*";
    }
    String[] tokens = raw.split(";");
    String primary = stripStateDecorations(tokens[0]);
    boolean conditional = tokens[0].contains("(") || tokens.length > 1;
    if (conditional) {
      gaps.add(GapEntry.builder()
          .sheet("CaseEvent")
          .rowKey(eventId)
          .column(Columns.POST_CONDITION_STATE)
          .value(raw)
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.CONDITIONAL_CODE)
          .detail("Conditional/multiple post-condition states are not expressible via the SDK's "
              + "single post-state builder; the event ends in the primary state '" + primary
              + "' and the conditional alternatives are dropped (an accepted semantic difference). "
              + "Reimplement the runtime transition in an aboutToSubmit callback that returns "
              + ".state(<computed state>).")
          .build());
    }
    return primary;
  }

  private List<PageModel> buildPages(List<SheetRow> fieldRows, List<SheetRow> complexRows) {
    Map<String, List<SheetRow>> byPage = new LinkedHashMap<>();
    for (SheetRow row : fieldRows) {
      String pageId = row.getString(Columns.PAGE_ID).orElse("1");
      byPage.computeIfAbsent(pageId, k -> new ArrayList<>()).add(row);
    }

    Map<String, List<SheetRow>> complexByField = groupBy(complexRows, Columns.CASE_FIELD_ID);

    List<PageModel> pages = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byPage.entrySet()) {
      List<SheetRow> rows = entry.getValue();
      List<PageModel.PageField> fields = new ArrayList<>();
      for (SheetRow row : rows) {
        String fieldId = row.getString(Columns.CASE_FIELD_ID).orElse("");
        fields.add(PageModel.PageField.builder()
            .caseFieldId(fieldId)
            .displayContext(row.getString(Columns.DISPLAY_CONTEXT).orElse(null))
            .displayContextParameter(row.getString(Columns.DISPLAY_CONTEXT_PARAMETER).orElse(null))
            .label(row.getDisplayText(Columns.CASE_EVENT_FIELD_LABEL).orElse(null))
            .hint(row.getDisplayText(Columns.CASE_EVENT_FIELD_HINT).orElse(null))
            .showCondition(row.getString(Columns.FIELD_SHOW_CONDITION).orElse(null))
            .showSummary(row.getYesNo(Columns.SHOW_SUMMARY_CHANGE_OPTION).orElse(null))
            .defaultValue(row.getString(Columns.DEFAULT_VALUE).orElse(null))
            .retainHiddenValue(row.getYesNo(Columns.RETAIN_HIDDEN_VALUE).orElse(null))
            .publish(row.getYesNo(Columns.PUBLISH).orElse(null))
            .publishAs(row.getString(Columns.PUBLISH_AS).orElse(null))
            .nullifyByDefault(row.getYesNo(Columns.NULLIFY_BY_DEFAULT).orElse(null))
            .showSummaryContentOption(
                row.getInteger(Columns.SHOW_SUMMARY_CONTENT_OPTION).orElse(null))
            .displayOrder(row.getInteger(Columns.PAGE_FIELD_DISPLAY_ORDER).orElse(null))
            .pageColumnNumber(row.getInteger(Columns.PAGE_COLUMN_NUMBER).orElse(null))
            .complexTypeOverrides(complexOverrides(complexByField.get(fieldId)))
            .build());
      }
      // PageLabel/PageShowCondition/PageDisplayOrder are page-scoped but a definition may carry
      // them on any field row of the page (often not the first), so scan all rows for the first
      // present value rather than reading only rows.get(0).
      pages.add(PageModel.builder()
          .pageId(entry.getKey())
          .label(firstPresentDisplayText(rows, Columns.PAGE_LABEL))
          .displayOrder(firstPresentInt(rows, Columns.PAGE_DISPLAY_ORDER))
          .showCondition(firstPresent(rows, Columns.PAGE_SHOW_CONDITION))
          .fields(fields)
          .build());
    }
    return pages;
  }

  private String firstPresent(List<SheetRow> rows, String column) {
    for (SheetRow row : rows) {
      Optional<String> value = row.getString(column);
      if (value.isPresent()) {
        return value.get();
      }
    }
    return null;
  }

  private String firstPresentDisplayText(List<SheetRow> rows, String column) {
    for (SheetRow row : rows) {
      Optional<String> value = row.getDisplayText(column);
      if (value.isPresent()) {
        return value.get();
      }
    }
    return null;
  }

  private Integer firstPresentInt(List<SheetRow> rows, String column) {
    for (SheetRow row : rows) {
      Optional<Integer> value = row.getInteger(column);
      if (value.isPresent()) {
        return value.get();
      }
    }
    return null;
  }

  private Map<String, Map<String, Object>> complexOverrides(List<SheetRow> rows) {
    Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
    if (rows == null) {
      return overrides;
    }
    for (SheetRow row : rows) {
      String code = row.getString(Columns.LIST_ELEMENT).orElse(
          row.getString(Columns.LIST_ELEMENT_CODE).orElse(""));
      overrides.put(code, new LinkedHashMap<>(row.getColumns()));
    }
    return overrides;
  }

  private List<TabModel> buildTabs(DefinitionIr ir, String caseTypeId) {
    Map<String, List<SheetRow>> byTab =
        groupBy(ir.rowsForCaseType(SheetName.CASE_TYPE_TAB, caseTypeId), Columns.TAB_ID);
    List<TabModel> tabs = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byTab.entrySet()) {
      List<SheetRow> rows = entry.getValue();
      SheetRow first = rows.get(0);
      List<TabModel.TabField> fields = new ArrayList<>();
      for (SheetRow row : rows) {
        fields.add(TabModel.TabField.builder()
            .caseFieldId(row.getString(Columns.CASE_FIELD_ID).orElse(""))
            .displayOrder(row.getInteger(Columns.TAB_FIELD_DISPLAY_ORDER).orElse(null))
            .showCondition(row.getString(Columns.FIELD_SHOW_CONDITION).orElse(null))
            .displayContextParameter(row.getString(Columns.DISPLAY_CONTEXT_PARAMETER).orElse(null))
            .label(row.getDisplayText(Columns.LABEL).orElse(null))
            .build());
      }
      // TabLabel, TabShowCondition and the tab's role (UserRole/AccessProfile) are tab-scoped: the
      // definition store applies them to the whole tab regardless of which field row carries them,
      // and a definition may set the role on any row (sscs's summary_sscs carries it only on a
      // mid-tab field). Scan all rows for the first present value rather than reading rows.get(0).
      String tabRole = firstPresent(rows, Columns.ACCESS_PROFILE);
      if (tabRole == null) {
        tabRole = firstPresent(rows, Columns.USER_ROLE);
      }
      tabs.add(TabModel.builder()
          .tabId(entry.getKey())
          .label(firstPresentDisplayText(rows, Columns.TAB_LABEL))
          .displayOrder(first.getInteger(Columns.TAB_DISPLAY_ORDER).orElse(null))
          .showCondition(firstPresent(rows, Columns.TAB_SHOW_CONDITION))
          .userRole(tabRole)
          .fields(fields)
          .build());
    }
    return tabs;
  }

  private List<SearchFieldModel> buildSearchFields(
      DefinitionIr ir, String caseTypeId, SheetName sheet, boolean cases) {
    List<SearchFieldModel> fields = new ArrayList<>();
    for (SheetRow row : ir.rowsForCaseType(sheet, caseTypeId)) {
      // Both the four SearchBuilder sheets (cases=false) and SearchCasesResultFields (cases=true)
      // now carry ListElementCode / ResultsOrdering / DisplayContextParameter (and, for cases, a
      // role/UserRole scope and the UseCase) via their per-field lambda, so every row is emitted as
      // Java — no passthrough. A single (field, role/useCase) legitimately carries several LEC rows,
      // one per leaf. FieldShowCondition is a SearchBuilder-only column (the SearchCases sheet does
      // not model it), so it is carried only when cases=false.
      fields.add(SearchFieldModel.builder()
          .caseFieldId(row.getString(Columns.CASE_FIELD_ID).orElse(""))
          .label(row.getDisplayText(Columns.LABEL).orElse(null))
          .displayOrder(row.getInteger(Columns.DISPLAY_ORDER).orElse(null))
          .displayContextParameter(row.getString(Columns.DISPLAY_CONTEXT_PARAMETER).orElse(null))
          .listElementCode(row.getString(Columns.LIST_ELEMENT_CODE).orElse(null))
          .showCondition(cases ? null : row.getString(Columns.FIELD_SHOW_CONDITION).orElse(null))
          .useCase(cases ? row.getString(Columns.USE_CASE).orElse(null) : null)
          .resultsOrdering(row.getString(Columns.RESULTS_ORDERING).orElse(null))
          .userRole(row.getString(Columns.ACCESS_PROFILE, Columns.USER_ROLE).orElse(null))
          .build());
    }
    return fields;
  }

  /**
   * The banner from the {@code Banner} sheet, or null when the input carries no banner. The importer
   * allows exactly one banner per jurisdiction (four columns), reproduced via
   * {@code ConfigBuilder.banner(enabled, description, url, urlText)} rather than passthrough.
   *
   * @param ir the definition IR
   * @return the banner model, or null
   */
  private BannerModel buildBanner(DefinitionIr ir) {
    if (!ir.hasSheet(SheetName.BANNER)) {
      return null;
    }
    for (SheetRow row : ir.rows(SheetName.BANNER)) {
      // The sheet carries one row; take the first that declares any banner column. Definitions and
      // the SDK's BannerGenerator both spell the link columns BannerUrl/BannerUrlText (camelCase);
      // accept the BannerURL/BannerURLText caps aliases too.
      boolean enabled = rowFlag(row, Columns.BANNER_ENABLED);
      String description = row.getDisplayText(Columns.BANNER_DESCRIPTION).orElse(null);
      String url = row.getString("BannerUrl", Columns.BANNER_URL).orElse(null);
      String urlText = row.getString("BannerUrlText", Columns.BANNER_URL_TEXT).orElse(null);
      if (enabled || description != null || url != null || urlText != null) {
        return BannerModel.builder()
            .enabled(enabled)
            .description(description)
            .url(url)
            .urlText(urlText)
            .build();
      }
    }
    return null;
  }

  /**
   * Reads a boolean CaseType-scoped flag column from the case type's own row. The value may be a
   * JSON boolean (civil) or a {@code Yes}/{@code No} string (ia/ET); {@code getYesNo} canonicalises
   * both.
   */
  private boolean caseTypeFlag(SheetRow caseType, String column) {
    return rowFlag(caseType, column);
  }

  /**
   * Reads a boolean flag column off a row, treating a JSON boolean and a {@code Yes}/{@code Y}/
   * {@code true} string identically (YN canon on read) and an absent/blank cell as false.
   */
  private boolean rowFlag(SheetRow row, String column) {
    return row.getYesNo(column).orElse(false);
  }

  /**
   * Whether every input {@code CaseRoles} row carries a {@code JurisdictionID}. The SDK's
   * {@code emitCaseRoleJurisdiction()} is an all-or-nothing switch — it stamps the jurisdiction on
   * every generated {@code CaseRoles} row — so it can only reproduce the input when all rows carry
   * the column. When only some do, the switch cannot be used; false is returned and the column is
   * grafted for those rows via {@code buildCaseRoleColumnPassthrough}, with a gap recorded here so
   * the mixed usage is visible in the report.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param gaps the gap collector
   * @return true when every CaseRoles row carries a JurisdictionID (and at least one row exists)
   */
  private boolean allCaseRolesCarryJurisdiction(
      DefinitionIr ir, String caseTypeId, GapCollector gaps) {
    List<SheetRow> rows = ir.rowsForCaseType(SheetName.CASE_ROLE, caseTypeId);
    int withId = 0;
    int total = 0;
    for (SheetRow row : rows) {
      if (row.getString(Columns.ID).isEmpty()) {
        continue;
      }
      total++;
      if (row.getString(Columns.JURISDICTION_ID).filter(v -> !v.isBlank()).isPresent()) {
        withId++;
      }
    }
    if (total == 0 || withId == 0) {
      return false;
    }
    if (withId == total) {
      return true;
    }
    gaps.add(GapEntry.builder()
        .sheet("CaseRoles")
        .rowKey(caseTypeId)
        .column(Columns.JURISDICTION_ID)
        .value(withId + "/" + total)
        .category(GapCategory.UNSUPPORTED_VALUE)
        .action(GapAction.OMITTED_FAIL)
        .detail("Only " + withId + " of " + total + " CaseRoles rows carry a JurisdictionID;"
            + " emitCaseRoleJurisdiction() is all-or-nothing (it stamps every generated row), so"
            + " mixed usage is not supported. Conversion fails unless --allow-gaps is set (which"
            + " omits the per-row JurisdictionID). Give every CaseRoles row a JurisdictionID to"
            + " convert it via the native switch.")
        .build());
    return false;
  }

  /**
   * The set of complex CaseField IDs whose {@code grantComplexType} member reference resolves to a
   * plain {@code CaseData::getX} getter — i.e. an emitted, non-clustered, non-overlay CaseField. A
   * grant on a field folded into a {@code @JsonUnwrapped} cluster, an overlay-only field, or a
   * field the converter did not emit cannot be expressed via the typed {@code grantComplexType}
   * getter, so those rows stay a passthrough.
   *
   * @param caseFields the emitted CaseData fields (with access classes attached)
   * @param clusteredRefs the clustered-field reference map (folded fields keyed by CCD id)
   * @return the resolvable complex-auth field IDs
   */
  private Set<String> resolvableComplexAuthFields(
      List<FieldModel> caseFields, Map<String, ClusteredFieldRef> clusteredRefs) {
    Set<String> resolvable = new LinkedHashSet<>();
    Map<String, ClusteredFieldRef> refs = clusteredRefs == null ? Map.of() : clusteredRefs;
    for (FieldModel field : caseFields) {
      // A clustered field is placed via .complex(parent) rather than a direct getter, and an
      // overlay-only field is not on CaseData at all; neither can back a grantComplexType getter.
      if (refs.containsKey(field.getId())) {
        continue;
      }
      if (field.getOverlayTags() != null && !field.getOverlayTags().isEmpty()) {
        continue;
      }
      resolvable.add(field.getId());
    }
    return resolvable;
  }

  /**
   * Links the {@code AuthorisationComplexType} sheet: every row whose complex field resolves to a
   * plain CaseData getter and whose role is a registered {@code UserRole} becomes a flat
   * {@link ComplexTypeAuthModel} the emitter reproduces via {@code builder.grantComplexType(...)};
   * any row that does not resolve stays a verbatim passthrough. The input's three shapes (flat
   * singular {@code UserRole}, flat {@code UserRoles[]} array, nested {@code AccessControl[]}) are
   * expanded to per-(role, CRUD) grants via {@link AuthorisationRows}, matching the flattening
   * {@code ccd-definition-processor} performs at build time — so the SDK's flat generator output
   * equals the imported form of every input shape. Because the generated grants are emitted as flat
   * per-role rows, the comparator's {@code AccessControlExpansionRule} now flattens this sheet too
   * (its per-sheet exclusion is removed) so the input array shapes compare against the flat output.
   *
   * @param ir the definition IR
   * @param options the conversion options (supplies overlay predicates)
   * @param gaps the gap collector
   * @param resolvableFields complex CaseField IDs whose getter resolves (see
   *     {@link #resolvableComplexAuthFields})
   * @param registeredRoles the registered UserRole IDs
   * @return the linked grants and the residual passthrough
   */
  private ComplexTypeAuthResult linkComplexTypeAuthorisations(
      DefinitionIr ir, ConversionOptions options, GapCollector gaps,
      Set<String> resolvableFields, Set<String> registeredRoles) {
    if (!ir.hasSheet(SheetName.AUTHORISATION_COMPLEX_TYPE)) {
      return new ComplexTypeAuthResult(List.of(), List.of());
    }
    List<ComplexTypeAuthModel> grants = new ArrayList<>();
    // De-duplicate expanded grants: a definition legitimately re-reads the same
    // AuthorisationComplexType row from several fragment files (fpl ships both a combined file and
    // per-field fragments), and the definition store keys rows on
    // (CaseFieldID, ListElementCode, UserRole), so an exact (field, LEC, role) repeat imports as one.
    // Emitting one grantComplexType per repeat would bloat the generated config with thousands of
    // duplicate serializable lambdas (all collapsing to the same generated row), so dedupe here.
    Set<String> seenGrants = new LinkedHashSet<>();
    // Residual rows that cannot be expressed via grantComplexType, bucketed by (shape, suffix) as
    // before so the mixed shapes never collapse on merge.
    Map<String, List<Map<String, Object>>> residualByBucket = new LinkedHashMap<>();
    Map<String, String> suffixByBucket = new LinkedHashMap<>();
    int emitted = 0;
    int residual = 0;
    for (SheetRow row : ir.rows(SheetName.AUTHORISATION_COMPLEX_TYPE)) {
      String fieldId = row.getString(Columns.CASE_FIELD_ID).orElse(null);
      String listElementCode = row.getString(Columns.LIST_ELEMENT_CODE).orElse(null);
      Map<String, String> roleGrants = AuthorisationRows.roleGrants(row);
      boolean fieldResolves = fieldId != null && resolvableFields.contains(fieldId);
      boolean allRolesRegistered = !roleGrants.isEmpty()
          && roleGrants.keySet().stream().allMatch(registeredRoles::contains);
      // Only overlay-base (unsuffixed) rows can be emitted as builder grants: grantComplexType has
      // no per-environment gate, so an overlay-suffixed grant would emit unconditionally. Route
      // suffixed rows to the residual passthrough (as before) so the overlay predicate is honoured.
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      if (fieldResolves && allRolesRegistered && listElementCode != null && suffix == null) {
        for (Map.Entry<String, String> grant : roleGrants.entrySet()) {
          if (!seenGrants.add(
              fieldId + "\u001f" + listElementCode + "\u001f" + grant.getKey()
                  + "\u001f" + grant.getValue())) {
            continue;
          }
          grants.add(ComplexTypeAuthModel.builder()
              .caseFieldId(fieldId)
              .listElementCode(listElementCode)
              .role(grant.getKey())
              .crud(grant.getValue())
              .build());
        }
        emitted++;
      } else {
        String shape = complexTypeAuthShape(row.getColumns());
        String bucket = shape + "\u001f" + suffix;
        residualByBucket.computeIfAbsent(bucket, k -> new ArrayList<>())
            .add(new LinkedHashMap<>(row.getColumns()));
        suffixByBucket.put(bucket, suffix);
        residual++;
      }
    }
    List<PassthroughSheet> passthrough = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : residualByBucket.entrySet()) {
      String shape = entry.getKey().substring(0, entry.getKey().indexOf('\u001f'));
      String suffix = suffixByBucket.get(entry.getKey());
      passthrough.add(PassthroughSheet.builder()
          .relativePath("AuthorisationComplexType/" + shape + ".json")
          .primaryKeys(complexTypeAuthKeys(shape))
          .overlaySuffix(suffix)
          .overlayCondition(OverlayResolver.conditionFor(suffix, options))
          .rows(entry.getValue())
          .build());
    }
    if (residual > 0) {
      gaps.add(GapEntry.builder()
          .sheet(SheetName.AUTHORISATION_COMPLEX_TYPE.getName())
          .rowKey(SheetName.AUTHORISATION_COMPLEX_TYPE.getName())
          .column(null)
          .value(emitted + " emitted / " + residual + " passthrough")
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.PASSTHROUGH_ROW)
          .detail("AuthorisationComplexType: " + emitted + " row(s) emitted via grantComplexType;"
              + " " + residual + " row(s) with an unresolvable field, unregistered role or overlay"
              + " suffix passed through as raw JSON")
          .build());
    }
    return new ComplexTypeAuthResult(grants, passthrough);
  }

  private AccessDerivation deriveAccessClasses(
      DefinitionIr ir,
      String caseTypeId,
      List<FieldModel> caseFields,
      List<EventModel> events,
      List<TabModel> tabs,
      List<List<SearchFieldModel>> searchSheets,
      GapCollector gaps) {
    List<String> fieldIds = caseFields.stream().map(FieldModel::getId).toList();
    Set<String> fieldIdSet = new LinkedHashSet<>(fieldIds);

    Map<String, Map<String, String>> desired = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.AUTHORISATION_CASE_FIELD, caseTypeId)) {
      Optional<String> fieldId = row.getString(Columns.CASE_FIELD_ID);
      if (fieldId.isEmpty()) {
        continue;
      }
      // A row grants a single flat role (UserRole/AccessProfile), or many at once via a UserRoles
      // or AccessControl array — the shapes ccd-definition-processor expands to per-role rows (see
      // access-control-transformer.js). Expand them so the derived access classes reproduce every
      // role's grant; the SDK only ever emits flat per-role rows.
      desired.computeIfAbsent(fieldId.get(), k -> new LinkedHashMap<>())
          .putAll(AuthorisationRows.roleGrants(row));
    }

    Map<String, Map<String, Set<Character>>> injected = new LinkedHashMap<>();
    Set<String> grantingRoles = new TreeSet<>();

    // The converter emits .explicitGrants() on every event (see EventsConfigEmitter), so mirror the
    // SDK's AuthorisationCaseFieldGenerator EXPLICIT branch here. In that branch an event grant does
    // NOT cascade onto the fields it places: for a mutable field the generator records the
    // (field, role) pair with an EMPTY permission set, and for an immutable field (READONLY page
    // field or a Label) it records CR. Which write wins for a field that is mutable on one event and
    // immutable on another depends on the generator's event/field iteration order, which the
    // converter cannot reproduce at link time, so this baseline injects NOTHING for event-placed
    // pairs. The access class then carries the field's full input CRUD: the SDK reproduces it
    // exactly when its baseline resolved to empty, and over-grants by at most CR when it resolved to
    // CR (a surplus the maintainer-accepted IMMUTABLE_FIELD_CR comparator rule forgives). An access
    // class can only ADD permissions, so injecting nothing here can never make the derivation
    // under-grant.
    //
    // The pair is still recorded in eventPlaced because the SDK writes it (empty or CR) as a column
    // key, and its tab/search read loops inject R only for pairs not already present, so an
    // event-placed pair suppresses that injected R (mirrored by the guards in those loops below).
    Set<String> eventPlaced = new LinkedHashSet<>();
    for (EventModel event : events) {
      Set<String> fieldsOnEvent = new LinkedHashSet<>();
      for (PageModel page : event.getPages()) {
        for (PageModel.PageField field : page.getFields()) {
          if (fieldIdSet.contains(field.getCaseFieldId())) {
            fieldsOnEvent.add(field.getCaseFieldId());
          }
        }
      }
      for (String role : event.getGrants().keySet()) {
        for (String fieldId : fieldsOnEvent) {
          // A role becomes a column key in the SDK's fieldRolePermissions only when an event it
          // grants actually places a case field on a page (the generator's per-field put). A role
          // that grants only field-less events (e.g. sscs citizen's createDraft/decisionIssued)
          // never enters that table, so the tab/search read loops — which iterate the existing
          // column keys — never inject R for it. Track such roles here, not merely any role holding
          // an event grant, so the tab/search injection below matches the generator exactly.
          grantingRoles.add(role);
          eventPlaced.add(pairKey(fieldId, role));
        }
      }
    }

    for (TabModel tab : tabs) {
      for (TabModel.TabField field : tab.getFields()) {
        if (!fieldIdSet.contains(field.getCaseFieldId())) {
          continue;
        }
        for (String role : rolesForTab(tab, grantingRoles)) {
          if (eventPlaced.contains(pairKey(field.getCaseFieldId(), role))) {
            continue;
          }
          addPerms(injected, field.getCaseFieldId(), role, Set.of('R'));
        }
      }
    }

    for (List<SearchFieldModel> sheet : searchSheets) {
      for (SearchFieldModel field : sheet) {
        if (!fieldIdSet.contains(field.getCaseFieldId())) {
          continue;
        }
        for (String role : rolesForSearch(field, grantingRoles)) {
          if (eventPlaced.contains(pairKey(field.getCaseFieldId(), role))) {
            continue;
          }
          addPerms(injected, field.getCaseFieldId(), role, Set.of('R'));
        }
      }
    }

    Map<String, Map<String, String>> injectedStrings = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Set<Character>>> entry : injected.entrySet()) {
      Map<String, String> roleMap = new LinkedHashMap<>();
      for (Map.Entry<String, Set<Character>> role : entry.getValue().entrySet()) {
        roleMap.put(role.getKey(), CrudSet.format(role.getValue()));
      }
      injectedStrings.put(entry.getKey(), roleMap);
    }

    AccessClassComputer computer = new AccessClassComputer(gaps);
    AccessClassComputer.Result result = computer.compute(fieldIds, desired, injectedStrings);
    return new AccessDerivation(result.accessClasses(), result.fieldClassNames(),
        result.summaryNote());
  }

  /**
   * Composes a stable {@code (fieldId, role)} key using a delimiter that cannot appear in a CCD
   * field ID or role name, so distinct pairs never collide.
   *
   * @param fieldId the case field ID
   * @param role the role name
   * @return the composite key
   */
  private static String pairKey(String fieldId, String role) {
    return fieldId + '\n' + role;
  }

  private Set<String> rolesForTab(TabModel tab, Set<String> allRoles) {
    if (tab.getUserRole() == null) {
      return allRoles;
    }
    return Set.of(tab.getUserRole());
  }

  private Set<String> rolesForSearch(SearchFieldModel field, Set<String> allRoles) {
    if (field.getUserRole() == null) {
      return allRoles;
    }
    return Set.of(field.getUserRole());
  }

  private void addPerms(
      Map<String, Map<String, Set<Character>>> injected,
      String fieldId,
      String role,
      Set<Character> perms) {
    injected.computeIfAbsent(fieldId, k -> new LinkedHashMap<>())
        .computeIfAbsent(role, k -> new LinkedHashSet<>())
        .addAll(perms);
  }

  private List<FieldModel> attachAccessClasses(
      List<FieldModel> caseFields, Map<String, List<String>> fieldClassNames) {
    List<FieldModel> result = new ArrayList<>();
    for (FieldModel field : caseFields) {
      List<String> names = fieldClassNames.getOrDefault(field.getId(), List.of());
      // Rebuild explicitly (rather than toBuilder) to preserve the pre-existing behaviour: the
      // access-derivation stage runs before clustering, so unwrapPrefix is always null here and
      // unknownType is not carried onto this list (the unknown-type passthrough is keyed off the
      // pre-attach caseFields). Only accessClassNames is newly attached.
      result.add(FieldModel.builder()
          .id(field.getId())
          .javaName(field.getJavaName())
          .fieldType(field.getFieldType())
          .fieldTypeParameter(field.getFieldTypeParameter())
          .javaType(field.getJavaType())
          .typeOverride(field.getTypeOverride())
          .typeParameterOverride(field.getTypeParameterOverride())
          .label(field.getLabel())
          .hint(field.getHint())
          .showCondition(field.getShowCondition())
          .regex(field.getRegex())
          .categoryId(field.getCategoryId())
          .searchable(field.getSearchable())
          .retainHiddenValue(field.getRetainHiddenValue())
          .min(field.getMin())
          .max(field.getMax())
          .accessClassNames(names)
          .overlayTags(field.getOverlayTags())
          .gate(field.getGate())
          .build());
    }
    return result;
  }

  private List<PassthroughSheet> buildPassthroughSheets(
      DefinitionIr ir, ConversionOptions options, GapCollector gaps) {
    // Banner is now emitted via builder.banner(...) (see buildBanner). The former whole-sheet
    // passthroughs for SearchAlias/UserProfile/AccessType/AccessTypeRole are retired: the SDK has no
    // API for any of them, and silently carrying them through as raw JSON hid a construct the
    // migrated Java definition cannot actually express. A definition that carries one of these sheets
    // now surfaces as a blocking OMITTED_FAIL gap instead (fails the conversion unless --allow-gaps),
    // so the migrating team makes a conscious decision about it rather than inheriting invisible JSON.
    failUnsupportedSheet(ir, SheetName.SEARCH_ALIAS, gaps);
    failUnsupportedSheet(ir, SheetName.USER_PROFILE, gaps);
    failUnsupportedSheet(ir, SheetName.ACCESS_TYPE, gaps);
    failUnsupportedSheet(ir, SheetName.ACCESS_TYPE_ROLE, gaps);
    return new ArrayList<>();
  }

  /**
   * Classifies an AuthorisationComplexType row by its access-control encoding: {@code nested} for
   * the {@code AccessControl[]} structure, {@code array} for a flat {@code UserRoles[]} list, and
   * {@code role} for the flat singular {@code UserRole} the SDK generator emits.
   *
   * @param columns the raw row columns
   * @return the shape discriminator used to name the fragment file and pick its merge keys
   */
  private String complexTypeAuthShape(Map<String, Object> columns) {
    if (columns.containsKey("AccessControl")) {
      return "nested";
    }
    if (columns.containsKey("UserRoles")) {
      return "array";
    }
    return "role";
  }

  private List<String> complexTypeAuthKeys(String shape) {
    return switch (shape) {
      case "nested" -> List.of(Columns.CASE_TYPE_ID, Columns.CASE_FIELD_ID,
          Columns.LIST_ELEMENT_CODE, "AccessControl");
      case "array" -> List.of(Columns.CASE_TYPE_ID, Columns.CASE_FIELD_ID,
          Columns.LIST_ELEMENT_CODE, Columns.CRUD, "UserRoles");
      default -> List.of(Columns.CASE_TYPE_ID, Columns.CASE_FIELD_ID,
          Columns.LIST_ELEMENT_CODE, Columns.CRUD, Columns.USER_ROLE);
    };
  }

  /**
   * Records a blocking {@link GapAction#OMITTED_FAIL} gap for a sheet the SDK cannot express and the
   * converter no longer passes through ({@code SearchAlias}, {@code UserProfile}, {@code AccessType},
   * {@code AccessTypeRole}). When the input carries the sheet, the conversion fails unless
   * {@code --allow-gaps} is set; when the sheet is absent, nothing is recorded.
   *
   * @param ir the definition IR
   * @param sheet the unsupported sheet
   * @param gaps the gap collector
   */
  private void failUnsupportedSheet(DefinitionIr ir, SheetName sheet, GapCollector gaps) {
    if (!ir.hasSheet(sheet)) {
      return;
    }
    int rowCount = ir.rows(sheet).size();
    gaps.add(GapEntry.builder()
        .sheet(sheet.getName())
        .rowKey(sheet.getName())
        .column(null)
        .value(String.valueOf(rowCount))
        .category(GapCategory.UNSUPPORTED_SHEET)
        .action(GapAction.OMITTED_FAIL)
        .detail("Sheet " + sheet.getName() + " has no config-generator equivalent and is not"
            + " supported: its " + rowCount + " row(s) cannot be expressed as Java. Conversion"
            + " fails unless --allow-gaps is set (which omits the sheet entirely).")
        .build());
  }

  /**
   * Passes the EventToComplexTypes sheet (aka CaseEventToComplexTypes) through verbatim. These
   * rows scope a display-context override to one member of a complex field on one event; the SDK
   * only re-derives them from {@code .complex(...)} builder blocks that carry per-member context,
   * which the converter's page-field emission does not reconstruct, so the rows have no generated
   * equivalent. The generator writes them under {@code CaseEventToComplexTypes/<event>/<field>.json},
   * so one passthrough file is produced per (overlay, event, field) with ListElementCode as the
   * merge key — matching how {@code CaseEventToComplexTypesGenerator} lays them out.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param options the conversion options (supplies overlay predicates)
   * @param gaps the gap collector
   * @return the passthrough sheets for EventToComplexTypes, one per (overlay, event, field)
   */
  private List<PassthroughSheet> buildEventToComplexTypesPassthrough(
      DefinitionIr ir, String caseTypeId, ConversionOptions options, GapCollector gaps) {
    List<SheetRow> rows = ir.rowsForCaseType(SheetName.CASE_EVENT_TO_COMPLEX_TYPES, caseTypeId);
    if (rows.isEmpty()) {
      return List.of();
    }
    // Key by (overlay suffix, event, field) so each lands in the right per-event/per-field file.
    Map<String, List<SheetRow>> byTarget = new LinkedHashMap<>();
    Map<String, String> suffixByTarget = new LinkedHashMap<>();
    for (SheetRow row : rows) {
      String eventId = row.getString(Columns.CASE_EVENT_ID).orElse("");
      String fieldId = row.getString(Columns.CASE_FIELD_ID).orElse("");
      if (eventId.isEmpty() || fieldId.isEmpty()) {
        continue;
      }
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      String key = suffix + "\u001f" + eventId + "\u001f" + fieldId;
      byTarget.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
      suffixByTarget.put(key, suffix);
    }
    List<PassthroughSheet> sheets = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byTarget.entrySet()) {
      String[] parts = entry.getKey().split("\u001f", -1);
      String eventId = parts[1];
      String fieldId = parts[2];
      String suffix = suffixByTarget.get(entry.getKey());
      List<Map<String, Object>> raw = new ArrayList<>();
      for (SheetRow row : entry.getValue()) {
        raw.add(new LinkedHashMap<>(row.getColumns()));
      }
      sheets.add(PassthroughSheet.builder()
          .relativePath("CaseEventToComplexTypes/" + eventId + "/" + fieldId + ".json")
          .primaryKeys(List.of(Columns.CASE_EVENT_ID, Columns.CASE_FIELD_ID,
              Columns.LIST_ELEMENT_CODE))
          .overlaySuffix(suffix)
          .overlayCondition(OverlayResolver.conditionFor(suffix, options))
          .rows(raw)
          .build());
    }
    gaps.add(GapEntry.builder()
        .sheet("EventToComplexTypes")
        .rowKey(caseTypeId)
        .column(null)
        .value(null)
        .category(GapCategory.UNSUPPORTED_SHEET)
        .action(GapAction.PASSTHROUGH_ROW)
        // The SDK now exposes per-member event overrides (.complex(parent).<ctx>(member) carrying
        // .eventLabel/.eventHint/.pageId + show condition — see EventComplexMember SDK test), but the
        // converter keeps the whole EventToComplexTypes sheet as a row-level passthrough: it already
        // round-trips BYTE-IDENTICALLY (zero residuals in every fixture baseline), whereas re-emitting
        // it as Java would require resolving each row's dotted ListElementCode into a nested member
        // getter chain — including through predefined SDK complex types whose members the converter
        // does not generate — for no fidelity gain and real regression risk. The exotic-tail columns
        // (SecurityClassification/Publish/ShowSummaryChangeOption/RetainHiddenValue/DefaultValue) ride
        // through on the same rows. Decision per the row-level-vs-column-graft rule: what stays
        // byte-identical stays row-level passthrough.
        .detail("EventToComplexTypes per-member event overrides carried as a byte-identical row-level"
            + " passthrough (the SDK's .complex(...) member setters are available for hand-written"
            + " Java but re-deriving nested member getters here is pure risk for zero residual gain); "
            + rows.size() + " rows passed through as raw JSON")
        .build());
    return sheets;
  }

  /**
   * Grafts the CaseEventToFields columns the SDK has no builder for back onto the generated rows via
   * column-level passthrough. The SDK writes one file per event ({@code CaseEventToFields/<event>.json})
   * merged on CaseFieldID. Per-field display metadata (FieldShowCondition, CaseEventFieldLabel/Hint,
   * DisplayContextParameter, DefaultValue, RetainHiddenValue) is now emitted as real Java by
   * {@code EventsConfigEmitter.fieldMetadataChain} for placed fields, so only the per-page mid-event
   * callback columns — which the converter deliberately emits no wiring for — remain grafted here.
   * Passing the input rows through (AddMissing) grafts those onto the generated rows without
   * overwriting the values the SDK computes, and adds rows for fields the SDK omits.
   *
   * @param ir the definition IR
   * @param caseTypeId the case type being converted
   * @param events the linked events (used to scope passthrough to emitted events)
   * @param options the conversion options (supplies overlay predicates)
   * @param gaps the gap collector
   * @return the CaseEventToFields column-passthrough sheets, one per (overlay, event)
   */
  private List<PassthroughSheet> buildEventFieldColumnPassthrough(
      DefinitionIr ir, String caseTypeId, List<EventModel> events, ConversionOptions options,
      GapCollector gaps) {
    Set<String> knownEvents = new LinkedHashSet<>();
    for (EventModel event : events) {
      knownEvents.add(event.getId());
    }
    // Only the mid-event callback columns are grafted now. Every per-field display-metadata column
    // the graft used to carry — FieldShowCondition, CaseEventFieldLabel/Hint, DisplayContextParameter,
    // DefaultValue and RetainHiddenValue — is emitted as real Java for placed fields by
    // EventsConfigEmitter.fieldMetadataChain via the all-context fluent FieldCollectionBuilder setters
    // (.fieldShowCondition/.caseEventFieldLabel/.caseEventFieldHint/.displayContextParameter/
    // .defaultValue/.retainHiddenValue), so grafting them too would be redundant (AddMissing would
    // skip the already-populated column anyway).
    //
    // The mid-event callback is a per-page CaseEventToFields property with NO builder API (the
    // converter emits no callback wiring at all), so the SDK writes no CallBackURLMidEvent and
    // grafting the input's raw value (env ${CCD_DEF_*} placeholders included) can never collide with
    // a generated one — the original mid-event endpoint round-trips byte-for-byte. Its retry policy
    // (RetriesTimeoutURLMidEvent / plain RetriesTimeoutMidEvent) is carried alongside. All three are
    // page-scoped, so PageLabelPropagationRule reconciles which row on the page carries them.
    Set<String> carried = Set.of(
        Columns.CALLBACK_URL_MID_EVENT,
        Columns.RETRIES_TIMEOUT_URL_MID_EVENT, "RetriesTimeoutMidEvent");

    Map<String, List<SheetRow>> byTarget = new LinkedHashMap<>();
    Map<String, String> suffixByTarget = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_EVENT_TO_FIELDS, caseTypeId)) {
      String eventId = row.getString(Columns.CASE_EVENT_ID).orElse("");
      String fieldId = row.getString(Columns.CASE_FIELD_ID).orElse("");
      if (!knownEvents.contains(eventId) || fieldId.isEmpty()) {
        continue;
      }
      // Skip CCD metadata placeholders ([STATE], [CASE_REFERENCE], …): the SDK never emits a
      // CaseEventToFields row for them, so a column graft keyed on CaseFieldID has no generated row
      // to merge into and PassthroughMerger would ADD an unkeyable orphan row. This only bites now
      // that mid-event callbacks are carried here (a metadata field can carry a page's mid-event
      // URL); when the page has another, real field it carries the same page-scoped URL and grafts
      // there instead, and when [STATE] is the page's only field the mid-event URL is genuinely
      // unreproducible via passthrough — the same outcome as before callbacks were carried.
      if (fieldId.startsWith("[") && fieldId.endsWith("]")) {
        continue;
      }
      String suffix = OverlayResolver.suffixFor(row.getOverlayTags(), options);
      String key = suffix + " " + eventId;
      byTarget.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
      suffixByTarget.put(key, suffix);
    }

    List<PassthroughSheet> sheets = new ArrayList<>();
    for (Map.Entry<String, List<SheetRow>> entry : byTarget.entrySet()) {
      String suffix = suffixByTarget.get(entry.getKey());
      String eventId = entry.getKey().substring(suffix == null ? "null ".length()
          : suffix.length() + 1);
      List<Map<String, Object>> raw = new ArrayList<>();
      for (SheetRow row : entry.getValue()) {
        Map<String, Object> out = new LinkedHashMap<>();
        // CaseFieldID is the merge key; keep it plus any carried metadata columns present.
        out.put(Columns.CASE_FIELD_ID, row.getString(Columns.CASE_FIELD_ID).orElse(""));
        for (String column : carried) {
          Object value = row.getColumns().get(column);
          if (value != null) {
            out.put(column, value);
          }
        }
        if (out.size() > 1) {
          raw.add(out);
        }
      }
      if (!raw.isEmpty()) {
        sheets.add(PassthroughSheet.builder()
            .relativePath("CaseEventToFields/" + eventId + ".json")
            .primaryKeys(List.of(Columns.CASE_FIELD_ID))
            .overlaySuffix(suffix)
            .overlayCondition(OverlayResolver.conditionFor(suffix, options))
            .rows(raw)
            .build());
      }
    }
    if (!sheets.isEmpty()) {
      gaps.add(GapEntry.builder()
          .sheet("CaseEventToFields")
          .rowKey(caseTypeId)
          .column(null)
          .value(null)
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.PASSTHROUGH_COLUMN)
          .detail("Per-page mid-event callback columns (CallBackURLMidEvent + its retry policy) "
              + "have no SDK builder equivalent (the converter emits no callback wiring); grafted "
              + "onto the generated CaseEventToFields rows via column passthrough. Per-field display "
              + "metadata (FieldShowCondition, CaseEventFieldLabel/Hint, DisplayContextParameter, "
              + "DefaultValue, RetainHiddenValue) is now emitted as Java and no longer grafted")
          .build());
    }
    return sheets;
  }

  private List<SheetRow> rowsFor(DefinitionIr ir, SheetName sheet, String caseTypeId) {
    return ir.rowsForCaseType(sheet, caseTypeId);
  }

  private Map<String, List<SheetRow>> groupById(List<SheetRow> rows) {
    return groupBy(rows, Columns.ID);
  }

  private Map<String, List<SheetRow>> groupBy(List<SheetRow> rows, String column) {
    Map<String, List<SheetRow>> byKey = new LinkedHashMap<>();
    for (SheetRow row : rows) {
      row.getString(column).ifPresent(key ->
          byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row));
    }
    return byKey;
  }

  private record AccessDerivation(
      List<AccessClassModel> accessClasses,
      Map<String, List<String>> fieldClassNames,
      String summaryNote) {
  }

  /** Bucket key pairing an overlay suffix (null for base) with a per-file target id. */
  private record SuffixTarget(String suffix, String target) {
  }

  /** The linked AuthorisationComplexType grants plus the residual (unresolvable) passthrough. */
  private record ComplexTypeAuthResult(
      List<ComplexTypeAuthModel> grants, List<PassthroughSheet> passthrough) {
  }
}
