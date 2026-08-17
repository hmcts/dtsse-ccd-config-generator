# JSON → Java Conversion: Round-Trip Fidelity

`ccd-definition-converter` converts a hand-written JSON CCD definition into config-generator
Java. The `round-trip` test suite (`RoundTripTest`) is the correctness proof: it converts a real
definition to Java, compiles it, runs the SDK's `generateCCDConfig`, and semantically diffs the
regenerated definition against the original input.

**Every construct the SDK can express round-trips byte-identically, modulo the enumerated gaps on
this page.** Each gap is classified one of three ways:

- **Not semantic** — a provably-equivalent spelling difference; the importer treats both forms
  identically. Forgiven by a named comparator rule (each rule class carries the justification in
  javadoc plus absorb-and-still-fails tests).
- **Semantic, accepted** — the regenerated definition genuinely differs, in a stated, bounded way
  the maintainer has accepted as permanent. Per-fixture residuals in this class are enumerated
  line-by-line in the CI-ratcheted baselines (`roundtrip-baselines/`); a new residual fails CI.
- **Fixed with passthrough** — the SDK has no API for the construct, so the converter carries the
  input JSON through verbatim (`PassthroughMerger` after generation). These round-trip **exactly** —
  the "gap" is only that the construct lives as JSON, not Java.

Separately, five constructs are **removed by design**: conversion hard-fails on them (an
`OMITTED_FAIL` gap, escapable with `--allow-gaps`, which omits them) so a migrating team makes a
conscious decision instead of inheriting invisible JSON — see
[Removed constructs](#removed-constructs-conversion-fails-by-design).

## Gap classification

| Gap | Classification | Handled by | Detail |
|---|---|---|---|
| `UserRoles[]`/`AccessControl[]` array shorthands vs flat per-role rows | Not semantic | `ACCESS_CONTROL_EXPANSION` | Both sides expanded to the flat rows the processor produces at build time (incl. `AuthorisationComplexType`) |
| Vestigial mid-event/retry columns on `CaseEvent` | Not semantic | `CASE_EVENT_MID_EVENT` | Importer has no such `CaseEvent` field; real mid-event lives on `CaseEventToFields` |
| About-to-start retry column naming | Not semantic | `CASE_EVENT_RETRIES` | Two spellings of the same column |
| Tab metadata spellings (`Channel` default, label/role propagation, role-suffixed `TabID`) | Not semantic | `CASE_TYPE_TAB` | Same tab, two encodings (also strips `TabFieldDisplayOrder` → display-order row below) |
| `Text` ≡ `String` collection element type | Not semantic | `COLLECTION_ELEMENT_TYPE` | Import identically |
| Generator-written CCD defaults vs omitted columns; importer-ignored metadata; whitespace-only labels | Not semantic | `DEFAULTS` | Only fires when the other side omits the column (also strips ordering columns → display-order row below) |
| Empty-`CRUD` authorisation row ≡ absent row | Not semantic | `EMPTY_CRUD_AUTHORISATION` | Importer rejects blank `CRUD`; grants nothing |
| `CRUD` letter order (`CUR` ≡ `CRU`) | Not semantic | `CRUD_LETTER_ORDER` | Importer parses `CRUD` as an order-independent set (`String.contains` per letter); a genuine set difference still fails |
| `""`/`null` ≡ absent column | Not semantic | `EMPTY_STRING_ABSENT` | Importer treats all identically |
| `Complex`+parameter ≡ direct type-ID spelling | Not semantic | `FIELD_TYPE_COMPLEX` | Same complex-type reference |
| Whitespace on identifier columns | Not semantic | `IDENTIFIER_WHITESPACE` | Importer trims identifiers for lookups |
| Column aliases (`UserRole`↔`AccessProfile`, `Name`↔`Label`, `CaseTypeId`↔`CaseTypeID`) | Not semantic | `KEY_ALIAS` | Importer accepts interchangeably |
| `LiveFrom` | Not semantic | `LIVE_FROM` | Mandatory-but-meaningless; `LiveTo` still compared (bar the vestigial-state exception below) |
| Duplicate `ComplexTypes` member with conflicting `ElementLabel` (prl fragments) | Not semantic | `CONFLICTING_ELEMENT_LABELS` | Importer keeps all rows in declaration order (no dedup); SDK emits the first-seen member — both sides collapse to first-seen |
| `CaseField`-sheet `DisplayContextParameter` | Not semantic | `DEFAULTS` | Importer's `CaseFieldParser` never reads it on the `CaseField` sheet (DCP is a per-page property); importer-ignored metadata |
| JSON number ≡ same value as string | Not semantic | `NUMERIC_STRINGS` | Importer coerces both |
| Page-scoped columns once-per-page vs repeated per row | Not semantic | `PAGE_LABEL_PROPAGATION` | Non-first-field `PageLabel` variation collapses (nothing renders it) |
| `PostConditionState=*` ≡ the event's single pre-state | Not semantic | `POST_CONDITION_NO_CHANGE` | Same runtime behaviour |
| Pre-condition state list order | Not semantic | `PRE_CONDITION_STATE_ORDER` | Unordered set |
| Importer-ignored `FieldTypeParameter` | Not semantic | `REDUNDANT_FIELD_TYPE_PARAMETER` | Only read for list/`Collection` types |
| `Public` ≡ `PUBLIC` | Not semantic | `SECURITY_CLASSIFICATION_CASE` | Importer case-insensitive |
| Whitespace inside show conditions | Not semantic | `SHOW_CONDITION_WHITESPACE` | Expression parser ignores it |
| State `Description` repeating `Name` | Not semantic | `STATE_DESCRIPTION` | The generator default (a *differing* Description is emitted via `@CCD(description)`) |
| `Yes`/`true` ≡ `Y` etc. on boolean columns | Not semantic | `YN_CANON` | Incl. `SignificantEvent`/`CanSaveDraft`/`EnableForDeletion`/`Shuttered`/`BannerEnabled` |
| `CaseType`-sheet `RetriesTimeoutURLPrintEvent` | Not semantic | `CASE_TYPE_PRINT_RETRIES` | The importer's `CaseTypeParser.parsePrintWebhook` builds the print webhook from `PrintableDocumentsUrl` alone and attaches no timeouts |
| `EventToComplexTypes` `CaseTypeID` and wizard-page columns | Not semantic | `EVENT_COMPLEX_TYPE_INERT_COLUMNS` | `CaseTypeID` is implied by traversal; `PageLabel`/`PageDisplayOrder`/`PageFieldDisplayOrder` are only read on `CaseEventToFields` |
| `Publish` on the `CaseField`/`ComplexTypes` sheets | Not semantic | `PUBLISH_IGNORED_ON_FIELD_SHEETS` | Publishing is a per-placement property; neither sheet's parser reads the column and neither entity carries it |
| Injected `caseHistory` field/tab/auth rows (creates/widens `CRU`) | **Semantic, accepted** | `CASE_HISTORY` | CaseHistoryViewer carries no submitted data; grants are display-only |
| Injected/widened read (`R`) on unrestricted-tab fields | **Semantic, accepted** | `TAB_READ_INJECTION` | Roles already event-granted gain tab-field visibility; each (field, role) is an `AUTH_NOT_DERIVABLE`/`ADVISORY` report-only record — **no row is passed through** ([detail](#authorisationcasefield-injected-read-records)) |
| Surplus `⊆ {C,R}` grants on `Label`/`READONLY` fields | **Semantic, accepted** | `IMMUTABLE_FIELD_CR` | Display permission on data-free fields |
| Uniform vestigial `AuthorisationCaseState LiveTo` (probate's `01/01/2020` on every row) | **Semantic, accepted** | `LIVE_TO_VESTIGIAL` | Dead sheet-wide end-of-life the SDK can't emit; per-row divergent `LiveTo` still fails ([detail](#3-uniform-vestigial-authorisationcasestate-liveto-live_to_vestigial)) |
| Display-order renumbering (`*DisplayOrder`/`PageColumnNumber` never compared, any sheet) | **Semantic, accepted** | `DEFAULTS` + `CASE_TYPE_TAB` strips | Relative order preserved by row order (FixedLists actively sorted); `PageColumnNumber=2` flattened. Includes `EventToComplexTypes` `FieldDisplayOrder` (SDK uses a per-event counter; relative member order preserved by emission order) |
| `EventToComplexTypes` `ID` (declaring complex type) | **Semantic, accepted** | `EVENT_COMPLEX_TYPE_ID_IGNORED` | The importer never reads `ID` on this sheet — `EventCaseFieldComplexTypeParser` maps `ListElementCode`/labels/order/context but not `ColumnName.ID`, it is not a required column, and `EventComplexTypeEntity.id` is a DB-generated sequence — so it is arbitrary author metadata dropped from both sides. Scoped to this sheet only (`ID` is a real key on `CaseField`/`ComplexTypes`/…) |
| Orphan ComplexTypes (nothing reachable references) | **Semantic, accepted** | `ORPHAN_COMPLEX_TYPE` | Not in `config.getTypes()`; the SDK generates no class/rows, so the input rows are dropped (advisory gap, "safe to delete") |
| Orphan-path FixedLists (reachable only via an orphan complex type) | **Semantic, accepted** | `ORPHAN_FIXED_LIST` | The SDK generates no enum; the input rows are dropped (advisory gap) |
| Predefined ComplexTypes redeclaration (member-by-member re-spelling of `Fee`/`Address`/…) | **Semantic, accepted** | `PREDEFINED_COMPLEX_TYPE_REDECLARATION` | The built-in `@ComplexType(generate=false)` type owns its definition; the redundant input rows are dropped (advisory gap) |
| Conditional / multi-target `PostConditionState` | **Semantic, accepted** | `CONDITIONAL_POST_STATE` | Runtime honours `state(cond):priority` (JEXL, first-match-wins); `EventBuilder` models one post-state, so the SDK emits only the primary and the alternatives are dropped ([detail](#4-conditional--multi-target-postconditionstate-collapse-conditional_post_state)) |
| Callback URLs + retries (all phases, incl. mid-event) | Fixed with passthrough | column-graft | Deliberate: no SDK callback wiring emitted; input URLs carried byte-exactly, `${CCD_DEF_*}` included, and compared exactly |
| Non-derivable `EventToComplexTypes` groups | Fixed with passthrough | row | A `(event, field)` group the member chains cannot express — unresolvable dotted `ListElementCode`, an undeclared field or event, non-O/M/R/C `DisplayContext`, a repeated `ListElementCode`, raw whitespace/case key quirks, or an overlay-suffixed sibling. The root field's placement context is **not** a cause ([detail](#5-eventtocomplextypes-generated-java-vs-fallback)) |
| `EventToComplexTypes` exotic-tail columns on derived groups | Fixed with passthrough | column-graft | `SecurityClassification`/`Publish`/`RetainHiddenValue`/… on a member-override row; grafted additively over the generated row (a group with no tail leaves no carrier) |
| FixedList whose ID collides with a ComplexType ID | Fixed with passthrough | row | The complex-type class takes the Java name, so no enum is generated; the list rows are carried verbatim (fpl `*Selector`, civil `Court`) |
| `AuthorisationComplexType` rows with an unresolvable field/role | Fixed with passthrough | row | Most rows emit via `grantComplexType(...)`; a row whose field is not a plain `CaseData` member or whose role is unregistered is carried verbatim (~24 rows, prl) |
| Per-field metadata for un-referenceable placements | Fixed with passthrough | column-graft | A field reached through a suppressed-getter `@JsonUnwrapped` parent, or a bracketed metadata `CaseFieldID` like `[STATE]`: no compilable getter exists, so the placement is skipped and the row's display metadata grafted (sscs `writeFinalDecision*`) |
| Overlay-only complex-type *members* (ET) | **Semantic, accepted** | fixture baseline (ratchet) | Members reachable only through an env-gated field; carried as enumerated lines in the ET baseline — a per-member `@CCD(gate)` emission would close them, none scheduled |
| `CaseField`-sheet `ShowSummaryContentOption` (`Y`, fpl/prl) | **Semantic, accepted** | fixture baseline (ratchet) | A CaseField-sheet flag distinct from the `CaseEventToFields` integer column (which is emitted); carried as enumerated baseline lines |

Anything not in this table that survives normalisation is a **real fidelity gap** — the round-trip
fails on it. Current open-gap totals per fixture are in
[Remaining residual tails](#remaining-residual-tails).

## Cosmetic normalisation rules

The rules live in
[`sdk/ccd-config-generator/src/testFixtures/java/uk/gov/hmcts/ccd/sdk/diff/`](../sdk/ccd-config-generator/src/testFixtures/java/uk/gov/hmcts/ccd/sdk/diff/)
and are applied by `NormalisingCcdConfigComparator` before a diff is judged a failure. Each rule
class carries the full justification in javadoc plus tests proving it absorbs exactly its shape and
still fails on real drift. Most are genuinely cosmetic; a few (`CASE_HISTORY`,
`TAB_READ_INJECTION`, `CONDITIONAL_POST_STATE`, `LIVE_TO_VESTIGIAL`) are really narrowly-scoped
*semantic* concessions — flagged in their bullets and kin to the accepted differences in tier 2. One
bullet each:

- **`ACCESS_CONTROL_EXPANSION`** — expands the `UserRoles[]` and `AccessControl[]` array shorthands
  on the `Authorisation*` sheets into the flat per-role rows `ccd-definition-processor` produces at
  build time; both sides are expanded identically. Runs first, whole-definition, so later rules see
  flattened rows. This includes **`AuthorisationComplexType`**: the converter emits it as flat
  per-role `grantComplexType` rows, so the sheet is flattened like every other `Authorisation*` sheet.
- **`CASE_EVENT_MID_EVENT`** — drops vestigial `CallBackURLMidEvent`/`RetriesTimeoutURLMidEvent`
  columns from the `CaseEvent` sheet; mid-event is a `CaseEventToFields` property and the importer
  has no such `CaseEvent` field. The callback itself round-trips on `CaseEventToFields`.
- **`CASE_EVENT_RETRIES`** — reconciles the `RetriesTimeoutAboutToStartEvent` column name with the
  generator's own naming for the about-to-start retry column.
- **`CASE_HISTORY`** — forgives the `caseHistory` field/tab/authorisation rows the generator
  unconditionally injects on every case type. This is a **semantic superset like `IMMUTABLE_FIELD_CR`**,
  not a cosmetic rewrite: on `AuthorisationCaseField` the rule *creates* a `CRU` grant row for any role
  that had none, and *widens* an input's narrower `⊆ {C,R,U}` grant up to `CRU` — so the regenerated
  definition grants more/wider `caseHistory` permissions than the input. Accepted because the
  CaseHistoryViewer field carries no submitted case data. The injected *tab* half is conditional:
  `CaseTypeTabGenerator` checks only the tab's **ID**, so a case type that shows case history from a
  tab of its own — sscs places `caseHistory` on a per-role `eventHistory_<role>` tab — would get a
  second History tab it never had. `ConfigBuilder.noCaseHistoryTab()` opts out of the injected tab
  alone (the `caseHistory` `CaseField` row and its authorisations are unaffected, so the field stays
  available to whichever tab does place it), and the converter calls it whenever the input declares no
  `TabID=CaseHistory`.
- **`CASE_TYPE_PRINT_RETRIES`** — drops `RetriesTimeoutURLPrintEvent` from the `CaseType` sheet. The
  print webhook is the one webhook the importer builds without timeouts:
  `CaseTypeParser.parsePrintWebhook` constructs its `WebhookEntity` from `PrintableDocumentsUrl`
  alone, where its sibling `parseGetCaseWebhook` calls
  `WebhookParser.parseWebhook(…, CALLBACK_GET_CASE_URL, RETRIES_GET_CASE_URL)` — and no `ColumnName`
  constant for a print-retries column exists at all (`CaseTypeParserTest` pins zero timeouts on the
  parsed print webhook). The column nonetheless survives in `ccd-template.xlsx`'s header row, so
  probate's `5,10,15` round-trips into the sheet and is then discarded by the importer. Scoped to the
  `CaseType` sheet and that one column; the print URL itself compares normally.
- **`CASE_TYPE_TAB`** — reconciles equivalent tab-metadata spellings: the `Channel=CaseWorker`
  default, `TabLabel`/`TabShowCondition` propagated to every field row (first non-blank wins; a
  genuine conflict still fails), the generator's role-scoped-tab encoding (role appended to `TabID`,
  written on the first field only, plus `AccessProfile` propagation) vs the hand-written form (plain
  `TabID`, `AccessProfile` repeated). `TabFieldDisplayOrder` is **stripped entirely from both sides**
  (not merely renumbered) — tab-field order is never verified, so a genuinely reordered tab passes;
  see [accepted difference 2](#2-display-order-renumbering).
- **`COLLECTION_ELEMENT_TYPE`** — `Text` (hand-written) ≡ `String` (SDK) as a text collection's
  `FieldTypeParameter`; both import identically.
- **`CONFLICTING_ELEMENT_LABELS`** — collapses a `ComplexTypes` member declared more than once on
  the expected side with differing `ElementLabel`s (prl ships some members in both a flat
  `ComplexTypes.json` and a `ComplexTypes/` fragment directory, and the fragments disagree on the
  label) down to the **first-seen** label before matching. A complex-type member is one Java field,
  so the converter keeps the first-seen declaration and records a gap
  (`DefaultDefinitionLinker`'s `seenTypes.putIfAbsent(code, …)` + `continue`); the importer does
  **not** dedup the sheet at all (`ComplexFieldTypeParser.parseComplexType` maps every row to its own
  `ComplexFieldEntity`, stored in the order-preserving `LinkedHashSet FieldTypeEntity.complexFields`
  fed from `DefinitionSheet.groupDataItemsById`'s `LinkedHashMap`), so the first-declared row is the
  first the store iterates — exactly the one the converter emits. Fires only when a member repeats
  with ≥2 distinct labels; a single-declaration label difference (or any non-label divergence between
  the duplicates) still fails.
- **`DEFAULTS`** — tolerates a generator-written CCD default standing in for an omitted column
  (only when the other side has no column at all): `SecurityClassification=Public`, `ShowSummary=N`,
  `ShowEventNotes=N`, `Publish=N`, `RetainHiddenValue=N`, `Searchable=Y` (generator only writes it
  when false), `EndButtonLabel="Save and continue"`, `PostConditionState=*`, `RoleToAccessProfiles`
  `Disabled`/`ReadOnly=N`. **`ShowSummaryChangeOption` is broader** — it forgives `Y`, `N` *and* `No`
  as "defaults", so a genuine `Y`-vs-omitted (or `N`-vs-omitted) difference on this column is dropped,
  not just a default-vs-omitted one; justified as display-only (it toggles the summary "change" link).
  Also drops **whitespace-only** `Label`/`ElementLabel`/`PageLabel` from each side (the exact run of
  blank whitespace is never compared). Also strips importer-ignored metadata: underscore-prefixed
  annotation columns (`_Comment`, `_Category`, …), the canonical `Comment`, `CaseTypeID` on the
  jurisdiction-global `ComplexTypes` sheet (the generator itself removes it), and
  `DisplayContextParameter` on the **`CaseField` sheet** — the importer's `CaseFieldParser`
  (`ccd-definition-store-api`, `excel-importer/.../parser/CaseFieldParser.java`) never reads DCP on
  the `CaseField` sheet (DCP is a per-page property, read on `CaseEventToFields`/`ComplexTypes`) and
  `CaseFieldEntity` has no field for it, so a `CaseField`-row DCP is importer-ignored and the SDK
  emits none (scoped to `CaseField`; DCP compares normally on every sheet that reads it). This rule
  also strips the
  ordering columns (`DisplayOrder`, `FieldDisplayOrder`, `PageFieldDisplayOrder`, `PageDisplayOrder`,
  `PageColumnNumber`, `TabDisplayOrder`) from both sides unconditionally — that is a *semantic*
  concession, not a cosmetic one; see [accepted difference 2](#2-display-order-renumbering).
- **`EMPTY_CRUD_AUTHORISATION`** — drops an authorisation row whose `CRUD` is empty; the importer
  rejects blank `CRUD`, so such a row grants nothing and equals an absent row.
- **`CRUD_LETTER_ORDER`** — canonicalises the letter order of the `CRUD` column on the five
  authorisation sheets (`AuthorisationCaseType`/`Field`/`Event`/`State`/`ComplexType`). The importer
  parses `CRUD` as an order-independent, case-insensitive *set* — `AuthorisationParser#parseCrud`
  (`ccd-definition-store-api`) sets each flag by `crud.toUpperCase().contains("C"/"R"/"U"/"D")` — so
  `CUR` and `CRU` grant identically. Both sides are rewritten to canonical `C,R,U,D` order before
  matching (it runs in `normaliseSheets` because `CRUD` is part of the `AuthorisationComplexType`
  primary key). A genuine set difference — a letter present on one side and absent on the other —
  sorts to a different string and still fails; a non-CRUD value is left untouched.
- **`EMPTY_STRING_ABSENT`** — an empty string or JSON `null` on one side equals an absent column on
  the other (and mutually blank/null columns collapse); the importer treats all identically.
- **`EVENT_COMPLEX_TYPE_INERT_COLUMNS`** — drops, from both sides of the
  `EventToComplexTypes`/`CaseEventToComplexTypes` sheet, the columns the importer never reads there:
  `CaseTypeID` (and its legacy `CaseTypeId` spelling) and the wizard-page columns `PageLabel`,
  `PageDisplayOrder`, `PageFieldDisplayOrder`. `EventParser.parseCaseEventComplexTypes` groups the
  sheet by `(CaseEventID, CaseFieldID)` and `EventCaseFieldComplexTypeParser` maps only
  `ListElementCode`, `EventElementLabel`, `EventHintText`, `LiveFrom`/`LiveTo`, `FieldDisplayOrder`,
  `DefaultValue`, the display context, `FieldShowCondition`, `Publish`/`PublishAs` and
  `RetainHiddenValue` — so the case type is implied by the traversal that reached the row
  (`ColumnName.isRequired` has no `CASE_EVENT_TO_COMPLEX_TYPES` branch for it), and the page columns
  are read only by `WizardPageParser`, whose constructor pins `sheetName = SheetName.CASE_EVENT_TO_FIELDS`.
  `FieldDisplayOrder` (an accepted display-order difference) and `PageID` are deliberately left alone.
- **`FIELD_TYPE_COMPLEX`** — `FieldType=Complex, FieldTypeParameter=<TypeId>` ≡ `FieldType=<TypeId>`
  — the same complex-type reference, two spellings.
- **`IDENTIFIER_WHITESPACE`** — trims leading/trailing whitespace on identifier columns
  (`ID`/`ListElementCode`/`FieldType`/`FieldTypeParameter`/`CaseFieldID`/`CaseEventID`/`CaseStateID`/
  `TabID`); the importer trims identifier cells for cross-sheet lookups. `FieldType` belongs there
  for the same reason as `FieldTypeParameter` — it is a lookup key into `ComplexTypes`/`FixedLists`,
  and the SDK necessarily emits it trimmed because it derives the name from a Java type (sscs authors
  both the `ID` and the `FieldType` of its `SearchCriteria` `CaseField` as `"SearchCriteria "`, with a
  trailing space). Never touches prose columns.
- **`KEY_ALIAS`** — canonicalises legacy column aliases the importer accepts interchangeably:
  `UserRole`→`AccessProfile`, `Name`→`Label` (CaseField), `CaseTypeId`→`CaseTypeID`, and on the
  `SearchParty` sheet the date-of-birth/date-of-death header casing — `ColumnName.SEARCH_PARTY_DOB`/
  `SEARCH_PARTY_DOD` spell them `SearchPartyDOB`/`SearchPartyDOD` and `SearchPartyGenerator` emits
  that casing, while sscs authors them `SearchPartyDoB`/`SearchPartyDoD`; the importer matches
  headers case-insensitively, so both import.
- **`LIVE_FROM`** — strips the mandatory-but-meaningless `LiveFrom` from both sides. `LiveTo` is
  deliberately left alone — an end-of-life date is behavioural — bar the one narrow accepted
  exception `LIVE_TO_VESTIGIAL` below.
- **`LIVE_TO_VESTIGIAL`** — a **semantic, accepted** concession (not cosmetic; see
  [accepted difference 3](#3-uniform-vestigial-authorisationcasestate-liveto-live_to_vestigial)): forgives a uniform,
  sheet-wide vestigial `LiveTo` the expected side carries on every `AuthorisationCaseState` row while
  the SDK emits none. Scoped narrowly — only that sheet, only the expected-carries/actual-omits shape,
  and only when *every* expected row carries the *identical* `LiveTo` value (probate's dead
  `01/01/2020`). A per-row divergent `LiveTo` (a real staggered end-of-life), or a `LiveTo` the actual
  side also carries, is left in place and still fails.
- **`NUMERIC_STRINGS`** — a numeric column as JSON number on one side equals the same value as a
  string on the other; the importer coerces both.
- **`PAGE_LABEL_PROPAGATION`** — propagates page-scoped columns (`PageLabel`, `PageShowCondition`)
  to every field row of the page before matching (hand-written definitions set them once per page;
  the generator repeats them). Per-field `PageLabel` variation within a page collapses to the first
  field's label (the only one CCD renders); a genuine difference on the page's first field still
  fails. The mid-event callback columns (`CallBackURLMidEvent`/`RetriesTimeoutURLMidEvent`) are
  **not** propagated: the converter emits no SDK mid-event wiring and instead carries the input's
  value through verbatim per field row (the `CaseEventToFields` column graft), so both sides already
  hold the input's exact per-row placement and compare directly — propagating would pick a page's
  "first" mid-event value, which on the rare page carrying two different mid-event URLs (a fixture
  inconsistency; only one fires) differs by side and would spuriously mismatch.
- **`ORPHAN_COMPLEX_TYPE`** — a **semantic, accepted** concession: drops an expected-side
  `ComplexTypes` declaration whose ID nothing reachable from a `CaseData` field references. The SDK
  generates no class or rows for such an orphan (it is never in `config.getTypes()`), so the converter
  drops it with an advisory gap and this rule forgives the expected-only rows. Self-contained: it
  recomputes reachability from the expected definition's own sheets (`DeclarationReachability`,
  mirroring `DefaultDefinitionLinker`) and drops a row only when its ID is genuinely unreachable **and**
  the actual side emitted no row for it — a reachable type the generator failed to emit (real drift), or
  a conflicting generated declaration, still fails.
- **`ORPHAN_FIXED_LIST`** — a **semantic, accepted** concession: drops an expected-side `FixedLists`
  declaration reachable only through an orphan complex type (or by nothing at all). The SDK generates no
  enum for it. Same self-contained reachability guard as `ORPHAN_COMPLEX_TYPE`; a list referenced by a
  field or a reachable complex member, or one whose ID also names a complex type (the collision case the
  converter still passes through), is never dropped.
- **`PREDEFINED_COMPLEX_TYPE_REDECLARATION`** — a **semantic, accepted** concession: drops an
  expected-side `ComplexTypes` declaration that spells out, member by member, an SDK-predefined platform
  type (fpl/civil's `Fee`, probate's `Address`). The built-in `@ComplexType(generate=false)` type owns
  its definition, so `ComplexTypeGenerator` emits no rows and referencing fields resolve to the built-in
  class; the converter drops the redundant rows with an advisory gap. The predefined ID set is reflected
  from `uk.gov.hmcts.ccd.sdk.type` (`PredefinedComplexTypes`) — the SDK's own source of truth, never a
  hand-coded list. Narrow: drops only when the actual side emitted no rows under that ID (a genuine
  generated declaration under the same ID — a conflict — is left in place and any real difference fails).
- **`POST_CONDITION_NO_CHANGE`** — `PostConditionState=*` equals the event's single pre-state;
  "no change" and "ends in that same state" are the same runtime behaviour.
- **`CONDITIONAL_POST_STATE`** — a **semantic, accepted** concession (not cosmetic; see
  [accepted difference 4](#4-conditional--multi-target-postconditionstate-collapse-conditional_post_state)): forgives an
  expected conditional/multi-target `PostConditionState` (`state(cond):priority`, or `;`-separated
  alternatives) collapsing to the single primary state the SDK's `EventBuilder` emits. Fires only on
  `CaseEvent`, only when the actual side equals the expression's primary state; the reverse shape and
  a disagreeing primary still fail. The runtime conditional transition is genuinely lost — a migrating
  team reimplements it via an `aboutToSubmit` callback.
- **`PUBLISH_IGNORED_ON_FIELD_SHEETS`** — drops a `Publish` column on the `CaseField` or
  `ComplexTypes` sheet from whichever side carries it. Publishing is a per-*placement* property: the
  column exists only on `CaseEventToFields` (`EventCaseFieldParser` → `EventCaseFieldEntity.publish`)
  and `EventToComplexTypes` (`EventCaseFieldComplexTypeParser` → `EventComplexTypeEntity.publish`),
  where it compares normally. Neither `CaseFieldParser.parseCaseField` nor
  `ComplexFieldTypeParser.parseComplexField` reads `ColumnName.PUBLISH`, and neither
  `CaseFieldEntity` nor `ComplexFieldEntity` has a field for it, so a `Publish` on a declaration row
  is inert. Scoped by a private `FIELD_SHEETS = Set.of("CaseField", "ComplexTypes")`.
- **`PRE_CONDITION_STATE_ORDER`** — `PreConditionState(s)` is an unordered set; the generator sorts
  it, the importer doesn't care.
- **`REDUNDANT_FIELD_TYPE_PARAMETER`** — drops a `FieldTypeParameter` the importer ignores on
  `CaseField`/`ComplexTypes` (it is only read for list types and `Collection`; real definitions
  frequently set it equal to the field ID on other types).
- **`SECURITY_CLASSIFICATION_CASE`** — `Public` ≡ `PUBLIC`; the importer is case-insensitive.
- **`SHOW_CONDITION_WHITESPACE`** — trims show-condition columns (`FieldShowCondition`/
  `PageShowCondition`/`TabShowCondition`/`EventEnablingCondition`); the expression parser treats
  the whitespace as insignificant.
- **`STATE_DESCRIPTION`** — forgives a state `Description` that merely repeats `Name`, the
  generator's default.
- **`USER_PROFILE_EXCLUDED`** — drops the whole `UserProfile` sheet from comparison, on both sides.
  Maintainer decision 2026-07-16: the sheet holds per-user
  workbasket-filter defaults that are deployment config, not case-type model, and are functionally
  dead in current XUI; the SDK still has no API for it, so conversion continues to hard-fail via the
  existing `OMITTED_FAIL`/`UNSUPPORTED_SHEET` gap (unless `--allow-gaps`) — this rule only stops the
  resulting expected-only rows from recurring as round-trip residuals.
- **`TAB_READ_INJECTION`** — a **semantic superset**, not cosmetic: the generator's tab loop injects
  read (`R`) on every field of an unrestricted tab for every already-granted role. The rule *widens*
  an input grant by a surplus `R` and *removes* actual-only `R` rows for roles that already hold
  another grant — so the regenerated definition grants read visibility on tab fields to roles the
  input did not. `AccessClassComputer` records each such (field, role) as an
  `AUTH_NOT_DERIVABLE` / **`ADVISORY`** gap — report-only, since no access class can subtract the
  injected read and (crucially) **no `AuthorisationCaseField` row is passed through**: the residual
  derivation emits access classes only. This comparator rule is what makes the round-trip clean; the
  gap entry is an honest record, not a load-bearing passthrough. Same mechanism as
  `IMMUTABLE_FIELD_CR` below, scoped to tab-derived reads (see
  [§ AuthorisationCaseField injected-read records](#authorisationcasefield-injected-read-records)).
- **`YN_CANON`** — canonicalises `Yes`/`Y`/`true` and `No`/`N`/`false` (case-insensitively) to
  `Y`/`N` on genuinely boolean columns only — never on numeric look-alikes such as
  `ShowSummaryContentOption`. Covers the definition-time flags the converter emits via builder
  switches: `SignificantEvent`, `EnableForDeletion`, `Shuttered` and `BannerEnabled` (fixtures ship
  these as `Yes`/`No` or JSON `true`/`false`; the generator writes `Y`/`N`). `DEFAULTS` then forgives
  an explicit `N` where the generator omits the column (it writes these only when the flag is true).

One further rule absorbs the accepted *semantic* difference below: **`IMMUTABLE_FIELD_CR`**.

### Comparator mechanics

Sheets are aggregated per-definition (file layout, per-file splits and filenames are never compared)
and rows matched by per-sheet primary keys (`SHEET_PRIMARY_KEYS` in `NormalisingCcdConfigComparator` —
e.g. `CaseEventToFields` = `(CaseEventID, CaseFieldID)`, the search/workbasket sheets include
`AccessProfile`/`ListElementCode`/`UseCase`). Exact-duplicate keyed rows are collapsed per side before
matching — the definition store keeps one row per key, so identical duplicates import as one, while a
same-key *content* conflict still fails. After normalisation the compare is `NON_EXTENSIBLE`: any
unmatched row or column fails.

Two mechanical consequences worth knowing:

- **Sheets with no `SHEET_PRIMARY_KEYS` entry** (`SearchCriteria` and any unknown sheet) fall back to
  **whole-row identity** as the key. A single-column drift on such a sheet then surfaces as an
  unmatched no-match/unexpected *row* pair rather than a precise column diff, and two identical rows
  collapse to one. (`SearchParty` *is* keyed, on `(CaseTypeID, SearchPartyName,
  SearchPartyCollectionFieldName)`.)
- **Row order within a sheet is never compared** — matching is purely key-based and generated rows are
  read in sorted-path order. Any sheet whose on-screen order the store derives from row position (see
  [accepted difference 2](#2-display-order-renumbering)) is therefore unverified for ordering.
- **The `CaseType` sheet's `Name` is compared un-substituted.** Every other interpreted column has its
  `${CCD_DEF_*}` placeholders resolved on the expected side before comparison, because the importer
  reads a resolved value (`Publish` also holds a placeholder in sscs, but is parsed through
  `getYesNo`, so the generator emits a resolved `Y`/`N`). `Name` is different: the linker reads it
  straight into `caseTypeName` and `CoreConfigEmitter` emits it as a string literal with placeholders
  intact, so a migrated service keeps its own `${CCD_DEF_*}` values and one build serves every
  environment. Substituting only the expected side manufactured a diff for whichever variables the
  harness happens to pass — sscs's `"SSCS Case ${CCD_DEF_VERSION} ${CCD_DEF_ENV}"` diverged on
  `CCD_DEF_ENV` alone while its five placeholder-bearing `CaseField` labels did not, purely because
  their variables were absent from the map (`RAW_CASE_TYPE_COLUMNS` in `ExpectedDefinitionBuilder`,
  shared by the in-JVM harness and the retrofit lanes).

## Accepted semantic differences

Permanent, accepted limitations of the SDK's generation model. The first is a genuine value
difference absorbed by a named rule that forgives exactly the accepted superset — never the
reverse shape, which would mask a regression. The second is an entire column family the comparator
excludes from comparison altogether.

### 1. Immutable-field CR injection (`IMMUTABLE_FIELD_CR`)

`AuthorisationCaseFieldGenerator` grants `CR` on every immutable field (`Label`, or `READONLY` on
the granting event) for any role holding a grant on the containing event — even under
`.explicitGrants()`, and per-event, so a field editable elsewhere but `READONLY` on the event
granting a role gets `CR` for that role only. Passthrough merging is add-only, so it cannot subtract
the surplus. The rule forgives a surplus `⊆ {C,R}` on rows in the derived immutable sets
(whole-field `Label`/always-`READONLY`, plus role-scoped `(field, role)` pairs); a surplus with
`U`/`D`, or on an ordinary editable field, still fails. *Why accepted*: Label/READONLY fields carry
no submitted case data — the extra `CR` is a display permission. *After migration*: if a Label
field's text itself is sensitive, it shouldn't be a bare Label field.

### 2. Display-order renumbering

The SDK derives ordering from **declaration order**, not explicit numbers: `FieldCollection`
assigns `PageFieldDisplayOrder` from a sequential counter as fields are declared, increments
`PageDisplayOrder` per `.page()` call, and hardcodes `PageColumnNumber=1`; tab order and tab-field
order likewise come from declaration order. There is no builder API to set any of these numbers
explicitly (events, NoC challenge questions and `Categories` are the exceptions — they have
`.displayOrder()`). So an input's `10, 20, 30`-style or gapped numbering regenerates as `1, 2, 3`.

**Absorbed by**: `DEFAULTS` strips `DisplayOrder`, `FieldDisplayOrder`, `PageFieldDisplayOrder`,
`PageDisplayOrder`, `PageColumnNumber` and `TabDisplayOrder` from both sides unconditionally;
`CASE_TYPE_TAB` strips `TabFieldDisplayOrder`. Unlike rules 1 and 2 this is not a scoped
superset-forgiveness — the numbering values are **never compared on any sheet**.

**Full scope**: because the bare header `DisplayOrder` is stripped on *every* sheet, ordering is
unverified not just for fields/pages/tabs but for **State**, **CaseEvent**, **FixedLists** option
order, all four **Search/WorkBasket** field sheets, **SearchCasesResultFields**, **ChallengeQuestion**
and **Categories**. For most of these the converter has no builder API and emits in **sheet row
order**, capturing the numbers into its models but not sorting by them — so relative order is
preserved only when the input's row order agrees with its numbering (the overwhelmingly common case),
and a definition whose rows are listed out of display order regenerates reordered and still passes.
Three sheets are different in that the converter *does* re-emit the number via a real builder API —
`CaseEvent` (`.displayOrder()`), NoC `ChallengeQuestion` (`.displayOrder()`) and `Categories`
(`.displayOrder()`) — so they would round-trip the value faithfully, yet it is stripped and compared
on none of them. **FixedLists** is the safe exception: `EnumEmitter` actively sorts the generated enum
constants by the captured input `DisplayOrder`, so a list whose JSON rows are out of order (e.g. ia's
`isoCountriesGovUk`) still regenerates in the correct on-screen order (only the numeric value
renumbers). Two-column page layouts (`PageColumnNumber=2` — used by e.g. civil's `generalapplication`
case type) are flattened to a single column, with no SDK API to express them.

**What to check after migration**: eyeball any wizard pages/tabs whose JSON row order you suspect
diverges from their `*DisplayOrder` numbering, and any page using `PageColumnNumber=2` — both need
manual reordering (or an SDK feature) rather than trust in the round-trip.

### 3. Uniform vestigial `AuthorisationCaseState LiveTo` (`LIVE_TO_VESTIGIAL`)

Probate's definition stamps an identical past-dated `LiveTo=01/01/2020` on **every**
`AuthorisationCaseState` row — a definition-wide vestige (its states have long since gone live) that
the SDK's state-authorisation model has no API to reproduce, so the generator emits no `LiveTo` there
at all. Because the value is uniform across the whole sheet it is dead metadata, not a genuine
staggered end-of-life; `LIVE_TO_VESTIGIAL` strips it from the expected side so the rows match.

*Why accepted*: a uniform sheet-wide `LiveTo` grants nothing different at runtime from an absent one
(every state's authorisation ended on the same past date, i.e. the column carries no per-row
information), and there is no builder API to set it. The rule is the tight inverse of `LIVE_FROM`'s
"`LiveTo` is behavioural" stance: it fires **only** on `AuthorisationCaseState`, **only** when the
expected side carries the column and the actual omits it, and **only** when every expected row shares
the identical value. A per-row divergent `LiveTo` (a real staggered end-of-life) or a `LiveTo` the
generated side also emits is left in place and still fails — so a regression that dropped a genuine
end-of-life date cannot hide behind this rule. It absorbs ~92% of probate's raw residual.

### 4. Conditional / multi-target `PostConditionState` collapse (`CONDITIONAL_POST_STATE`)

A CCD `PostConditionState` may be conditional or multi-target: `;`-separated entries, each
`state(enablingCondition):priority`, with a bare state as the default (priority 99). **The data store
honours these at runtime**: `CasePostStateService` prioritises the entries and
`CasePostStateEvaluationService` evaluates each JEXL condition first-match-wins, falling back to the
default (see `ccd-data-store-api`; the entities are imported and stored by
`ccd-definition-store-api`'s `EventPostStateParser`). So `startAppeal` ending in
`appealStartedByAdmin(isAdmin="Yes"):2;appealStarted` genuinely transitions to different states
depending on the case data at submit time.

The SDK's `EventBuilder`, however, models a single static post-state per event, so the converter
emits only the **primary** state (the first token's state ID — `DefaultDefinitionLinker#parsePostState`)
and drops the conditional alternatives. The maintainer accepts this collapse **knowingly**: the
regenerated definition transitions only to the primary state, and the runtime branch is lost. The
converter records a `CONDITIONAL_CODE` gap for each affected event, and the round-trip diff is
forgiven by the `CONDITIONAL_POST_STATE` comparator rule — which fires **only** on `CaseEvent`,
**only** when the expected value is genuinely conditional/multi, and **only** when the actual value
equals that expression's primary state. The reverse shape (a conditional the generator invented) and
a primary that disagrees both still fail, so a generator regression cannot hide behind it.

A *third* shape — no `PostConditionState` column at all — is expressible rather than collapsed.
An absent column is distinct from both a concrete state and `*`: the data store applies only the
state the about-to-submit callback returned, so writing any value there would force a
definition-declared transition the hand-written definition deliberately omitted.
`EventBuilder.postStateFromCallback()` suppresses the column entirely while leaving the pre-state
alone, so an event available in one state does not become available in all
([`CaseEventGeneratorTest`](../sdk/ccd-config-generator/src/test/java/uk/gov/hmcts/ccd/sdk/generator/CaseEventGeneratorTest.java)
pins all three behaviours); the converter emits it for an event whose input carries no post-state.

*What to do after migration*: a team that relies on the runtime conditional transition must
reimplement it in an `aboutToSubmit` callback that inspects the case data and returns
`AboutToStartOrSubmitResponse.<CaseData, State>builder().data(...).state(<computed state>).build()`.
This is the SDK-native pattern the reference services use — declare the pre-state(s) with
`forStates(...)`/`forStateTransition(...)`, register `.aboutToSubmitCallback(this::aboutToSubmit)`,
and compute the target state in the callback (see nfdiv's `Applicant1Resubmit` for a two-branch
example and `SubmitConditionalOrder` for a multi-way computed state; sptribs'
`CaseworkerCloseTheCase` corroborates).

### 5. EventToComplexTypes: generated Java vs. fallback

The `CaseEventToComplexTypes` sheet — per-member event display-context overrides scoped to one
complex field on one event — is emitted as generated Java `.complex(...)` builder chains wherever a
`(event, field)` group can be faithfully reproduced, falling back to a verbatim row passthrough only
for groups that cannot.

**Derivation.** For each `(event, field)` group the linker (`DefaultDefinitionLinker`
/ `EventComplexTypeResolver`) walks every row's dotted `ListElementCode` segment-by-segment through
the typed complex-type graph — generated `@ComplexType` classes by their model, SDK-predefined types
(`uk.gov.hmcts.ccd.sdk.type.*`) by reflection — mirroring the SDK's exact member-naming math
(`FieldUtils.getFieldId`: a generated member's id is its `javaName`, a predefined member's is its
`@JsonProperty` value or field name; the getter is `get` + `StringUtils.capitalize` of the Java field
name, so the input `OrganisationToAdd.OrganisationID` resolves to
`.complex(ChangeOrganisationRequest::getOrganisationToAdd).<ctx>(Organisation::getOrganisationId)`).
The emitter (`EventsConfigEmitter`) opens a member scope on the field and places each member as
`.optional`/`.mandatory`/`.readonly`/`.complexMember(Type::getMember)` carrying
`.eventLabel`/`.eventHint`/`.fieldShowCondition`/`.pageId`. This reproduces `DisplayContext`,
`ListElementCode`, `EventElementLabel`, `EventHintText`, `FieldShowCondition`, `PageID` and `HintText`.

**The scope is decoupled from the placement.** `FieldCollection.complex(getter)` does two jobs at
once: it registers a root `CaseEventToFields` row with `DisplayContext=COMPLEX` *and* opens a member
scope. That coupling cannot express a group whose root the event places as
`OPTIONAL`/`READONLY`/`MANDATORY` (sscs's `updateOtherPartyData/appeal`), or does not place at all
(sscs's `dwpUploadResponse/otherParties`) — deriving one would manufacture a `COMPLEX` placement the
input never had. A **non-registering scope opener** separates them: the scalar
`FieldCollection.complexScope(getter)` and the element-typed `complex(getter, Element.class)` both
open a member scope without registering any field, so they add no `CaseEventToFields` row. Only
`CaseEventToComplexTypesGenerator` reads `FieldCollection.getComplexFields()`, so such a scope reaches
that sheet and nothing else. The placement therefore emits the `CaseEventToFields` row and a
**separate** statement emits the members; neither implies the other. Three shapes fall out of this one
primitive:

- **Non-`COMPLEX` placement** — the root keeps the context the input asked for; the members go in a
  separate `fields.complexScope(getter)` statement.
- **No placement at all** — the group is emitted as an *orphan scope* after every page has been
  applied. Sound because a non-registering scope contributes no `CaseEventToFields` row, so it cannot
  disturb the display ordering the placements established. On an event with **no pages at all**
  (probate's `boFindMatchedCaseGrantRegistrarEscalation`) the emitter opens a bare `.fields()` purely
  to obtain the builder — `EventBuilder.fields()` returns the event's pre-built collection builder and
  registers nothing — and only does so when there is a scope to hang off it.
- **A member placed `COMPLEX` in its own right** — sscs's
  `confirmPoAttendance/presentingOfficersDetails`, where `contact` carries a `COMPLEX` row alongside
  dotted `contact.phone` rows. `FieldCollection.complexMember(getter)` places with
  `DisplayContext.Complex` *without* opening a nested scope, so the two compose on one intermediate.

**Collection roots and intermediates.** A `Collection`-typed root or intermediate member (getter
`List<ListValue<X>>`) is walked into via a dedicated SDK affordance rather than being rejected: the
element-typed scope overload `FieldCollection.complex(getter, Element.class)` opens a member block
typed on the element `X` (a plain `.complex(getter)` would type it on the `List` and a
`.mandatory(X::getMember)` inside would not compile; `.list(getter)` would change the field's
rendering). For a collection *root* the emitter still registers the collection field's own `COMPLEX`
row with the bare one-arg `.complex(CaseData::getField).done()` and places the element members in a
separate `.complex(CaseData::getField, Element.class)` statement (which registers no field, so it
adds no second `CaseEventToFields` row); a collection *intermediate* opens the two-arg
`.complex(hop, Element.class)` scope inline. Nested collection-in-collection paths compose the same
overload at each hop.

**HintText tri-state.** A leaf member's declared `@CCD(hint)` otherwise cascades onto every event row
placing it, so a row whose `HintText` differs from the declared hint would force a fallback. The
member row carries the input's `HintText` disposition via the SDK's tri-state carrier
(`FieldCollection.hintText(...)`/`.noHintText()`): the input `HintText` equal to the declared hint
leaves the cascade (nothing emitted), a differing `HintText` emits `.hintText(value)`, and an absent
`HintText` against a member that declares one emits `.noHintText()` to suppress the cascade. This is
distinct from `.eventHint(...)`, which writes the `EventHintText` column.

**Companion column-graft.** A derived group grafts back only its **exotic tail** — the columns the
SDK generator *cannot* compute — keyed on `(CaseEventID, CaseFieldID, ListElementCode)` and merged
**additively**: `SecurityClassification`, `Publish`, `RetainHiddenValue`, `ShowSummaryChangeOption`,
`ShowSummaryContentOption`, `DefaultValue`, `ElementLabel`, `FieldType`/`FieldTypeParameter`,
`Comment`/`_comment`, `CaseTypeID`, `Page*` — none of which the member builder expresses. When a
derived group's rows carry no such column, **no passthrough carrier is produced at all** — the
`.complex(...)` chain is the whole story. (`LiveFrom` is stripped on both sides by `LIVE_FROM`, so it
is neither derived nor grafted.)

Two columns the graft deliberately does **not** carry are **accepted differences** instead (maintainer
decision, backed by the definition-store importer — see the accepted-differences table):

- **`ID`** — the row's declaring complex type (e.g. ia's `lastModifiedApplication` field, declared type
  `makeAnApplication`, carries `ID=decideAnApplication`). The importer's
  `EventCaseFieldComplexTypeParser` never reads `ColumnName.ID` on this sheet, `ID` is not a required
  column for it, and `EventComplexTypeEntity.id` is a DB-generated sequence — so it is arbitrary author
  metadata that never reaches the imported definition. Dropped from comparison on both sides by
  `EVENT_COMPLEX_TYPE_ID_IGNORED`, so it need not be added to every derived row.
- **`FieldDisplayOrder`** — the input numbers members per complex field (restarting at 1, with author
  gaps/duplicates), whereas the SDK stamps a per-event running counter. Only the members' **relative**
  order matters (preserved by emitting members in input row order); the absolute value is not read for
  ordering. It joins the display-order-renumbering disposition — stripped on every sheet by `DEFAULTS`
  — so it is neither derived nor grafted.

**Fallback.** A whole group stays a verbatim, ID-keyed row passthrough when it is not derivable. **No
placement shape is a cause**: with the scope decoupled from the placement (above), whatever
`DisplayContext` the event places the root field in, and whether any page places it at all, is
irrelevant. Every cause is a *resolution* or *structural* failure:

- a dotted `ListElementCode` does not resolve through the typed graph — an unknown member, a scalar
  intermediate, or a hop into a type the converter neither generated nor can reflect (prl's
  `applicantOrganisationPolicy:lastNoCRequestedBy` against the predefined `OrganisationPolicy`);
- no `CaseField` row declares the field (civil's `CREATE_SDO/disposalHearingStandardDisposalOrder`);
- no `CaseEvent` row declares the event, so there is no generated `.event(...)` block to place a scope
  on. Two flavours, both irreducible: rows naming an event **nothing** declares (probate's 30
  `boFindMatchedCase*RegistrarEscalation` rows are dead definition data), and rows naming an event
  declared for a **sibling case type** (et's `importFile` belongs to `ET_EnglandWales_Multiple`);
- a member `DisplayContext` outside `OPTIONAL`/`MANDATORY`/`READONLY`/`COMPLEX`;
- a same-`ListElementCode` collision surviving exact-duplicate dedup (the two rows collapse to one
  generated row, so a passthrough would merge onto its derived twin instead of standing alongside it);
- a raw derivable-key value the generator would normalise away (surrounding whitespace on
  `CaseFieldID`/`ListElementCode`, or a title-case `DisplayContext`);
- an overlay-suffixed group, or an overlay-suffixed sibling row targeting the same file (the emitter
  does not per-environment-gate a member scope).

(An `ID` collision is not a cause — every surviving `ListElementCode` is unique within a group, so the
`(event, field, LEC)`-keyed graft disambiguates rows that carry different `ID`s.)

**Measured derived / fallback rows per fixture** (all seven round-trip byte-identically either way):
ia 258 / 0, sscs 746 / 0, probate 288 / 30, fpl 1876 / 19, civil 2367 / 143, et 870 / 144,
prl 5768 / 474. prl's fallback is dominated by unresolvable dotted paths against SDK-predefined types
and by groups repeating a `ListElementCode` twice within an event; et's and civil's by overlay-suffixed
groups.

### 6. AuthorisationCaseField injected-read records {#authorisationcasefield-injected-read-records}

The SDK's `AuthorisationCaseFieldGenerator` unconditionally injects a read (`R`) on every field of an
unrestricted `CaseTypeTab` for every role that already holds any grant (see the rule javadoc). An
`@CCD(access)` class can only *add* permissions, so this injected read can never be subtracted;
`AccessClassComputer.residual` therefore records each such `(field, role)` as an `AUTH_NOT_DERIVABLE`
gap.

**These records are report-only, not passthrough.** The residual derivation
(`DefaultDefinitionLinker.deriveAccessClasses`) returns access classes and nothing else — it produces
**no `AuthorisationCaseField` passthrough sheet** (empirically confirmed: no
`base/AuthorisationCaseField.json` is ever written), and `GapCollector` acts on only one action —
`OMITTED_FAIL`, via `hasBlockingGaps()`. What actually makes the round-trip clean is the
`TAB_READ_INJECTION` comparator rule, which forgives exactly this injected-`R` divergence on both the
matched-surplus and actual-only-row shapes. The gap entry is an honest "nothing is silently dropped"
record; it is **not** load-bearing.

**The count, and what it is.** This is the dominant `AUTH_NOT_DERIVABLE` category by row count —
**52,232 records across the seven fixtures** (ia 15,823, prl 11,958, fpl 11,676, civil 7,615,
et 3,595, sscs 323, probate 1,242). Every single one is the injected-read case: the `extra`
permission is `{R}` for **100%** of them, because the converter's only injection into the derivation's
`have` map is `Set.of('R')` from the tab and search loops — no other permission can appear there, so
there is no other-cause subset. These are **not** un-converted JSON: no row is carried through, and
the field/role's *intended* grant is derived into a genuine `@CCD(access)` class exactly as for any
other field. The 52k figure is a gap-report artifact — a per-(field, role) note about a display-only
over-grant the comparator forgives — not a measure of fidelity loss.

## Constructs carried by passthrough (not expressed in Java)

The converter reproduces these verbatim through `PassthroughMerger` (additive `JsonUtils.mergeInto`
after generation), because the SDK has no API for them. They round-trip exactly — they are not diff
exceptions — but they live as JSON under `--passthrough-dir`, not as Java. Each construct below is one
row (source: `DefaultDefinitionLinker`). *Mechanism* is the merge shape:

> **Measured scale (all six service fixtures — Benefit, fpl, civil, ET_EnglandWales, probate, prl).**
> Total passthrough artifacts: **~79**, breaking down as — `CaseEventToFields` column-grafts **57**
> (callbacks + skipped-unwrapped-field metadata, mostly sscs's `writeFinalDecision`), `FixedLists`
> ID-collision rows **15**, non-derivable `EventToComplexTypes` groups **5** (one summary row per
> fixture *with* a residual — ia and sscs have none; the underlying member rows total 810 against
> the ~12.2k emitted as Java), and
> `AuthorisationComplexType` unresolved rows **1**. There is **no** `AuthorisationCaseField`
> passthrough — the ~52k `AUTH_NOT_DERIVABLE` entries are report-only `ADVISORY` records of an
> injected read the comparator forgives (see [§6](#authorisationcasefield-injected-read-records)),
> not carried JSON.

- **whole-sheet** — the entire sheet passes through (the generator emits nothing for it).
- **row** — whole rows are added for records the generator omits.
- **column-graft** — only the named columns are grafted, additively, onto a generator-emitted row
  (never overwriting a value the generator computed).

(The **overwrite-graft** shape — named columns *replacing* the generator's value on a matched row — is
supported by `PassthroughMerger` but has no producer: the conditional `PostConditionState` and the
derived-group `FieldDisplayOrder` are accepted-semantic dispositions, and the unknown-`FieldType` graft
is a removed construct.)

| Construct | Sheet(s) | Mechanism | Why there is no SDK API |
|---|---|---|---|
| EventToComplexTypes — **derived-group tail** | `CaseEventToComplexTypes` (→ `EventToComplexTypes`) | column-graft (per event/field) | For a group emitted as generated `.complex(...)` Java (see [§5](#5-eventtocomplextypes-generated-java-vs-fallback)), only the **exotic tail** the generator cannot compute (`SecurityClassification`/`Publish`/`RetainHiddenValue`/…) is grafted onto the generated rows, additively, keyed on `(CaseEventID, CaseFieldID, ListElementCode)`. The row's `ID` (importer-ignored, `EVENT_COMPLEX_TYPE_ID_IGNORED`) and `FieldDisplayOrder` (SDK per-event counter; relative member order preserved by emission order — display-order disposition) are accepted differences rather than grafted columns; a derived group with no exotic tail leaves **no carrier at all**. |
| EventToComplexTypes — **non-derivable group** | `CaseEventToComplexTypes` (→ `EventToComplexTypes`) | row (per event/field) | A whole `(event, field)` group the converter cannot express as builder chains stays a verbatim row passthrough (ID-keyed). Causes: an unresolvable dotted `ListElementCode`, a field no `CaseField` row declares, an event no `CaseEvent` row declares (dead rows, or an event belonging to a sibling case type), a `DisplayContext` outside `OPTIONAL`/`MANDATORY`/`READONLY`/`COMPLEX`, a `ListElementCode` repeated within the group, a raw derivable-key value the generator would normalise (whitespace/case), or an overlay-suffixed sibling. (`Collection`-typed roots/intermediates, hint-cascade rows, and every root-placement shape — non-`COMPLEX`, unplaced, page-less — are derived; see [§5](#5-eventtocomplextypes-generated-java-vs-fallback).) |
| Callback URLs (about-to-start / about-to-submit / submitted) + their `RetriesTimeout*` | `CaseEvent` | column-graft | The converter deliberately emits **no** SDK callback wiring, so the generator writes no `CallBackURL*`/`RetriesTimeout*`; the input values (env `${CCD_DEF_*}` placeholders included) are grafted back verbatim. |
| Mid-event callback URL + its `RetriesTimeout*MidEvent` | `CaseEventToFields` | column-graft | Same: mid-event is a per-page property, carried verbatim per field row rather than wired (a bracketed metadata `CaseFieldID` such as `[STATE]` is skipped — the generator emits no row for it to graft onto). |
| Per-field metadata for a placement skipped as un-referenceable (`@JsonUnwrapped` parent whose getter the model suppresses with `@Getter(AccessLevel.NONE)`, or a bracketed metadata `CaseFieldID` like `[STATE]`) | `CaseEventToFields` | column-graft | No compilable typed getter reference exists for the field, so the placement is skipped in Java and the row's per-field display metadata is grafted back (sscs's `writeFinalDecision*` unwrapped members are the bulk of this). |
| FixedList whose ID collides with a ComplexType ID | `FixedLists` | row | When a definition declares a FixedList and a ComplexType under the same ID (fpl's `*Selector`, civil's `Court`), the complex-type class takes the Java name, so no enum is generated and the list's rows are carried verbatim. |
| `AuthorisationComplexType` rows with an unresolvable field/role | `AuthorisationComplexType` | row | Most rows emit via `grantComplexType(...)`; a row whose complex field does not resolve to a plain `CaseData` member, or whose role is not a registered `UserRole`, is carried verbatim (≈24 rows, all in prl). |

Anything not expressible as code *or* passthrough is an `OMITTED_FAIL` entry in the gap report and
fails the conversion unless `--allow-gaps`.

### Removed constructs (conversion fails by design) {#removed-constructs-conversion-fails-by-design}

Five constructs are removed by design — the converter records a blocking `OMITTED_FAIL` gap
(category `UNSUPPORTED_SHEET`/`UNSUPPORTED_VALUE`) so a definition carrying one fails conversion
unless `--allow-gaps` is set (which omits the construct entirely rather than fabricating it).
Silently carrying them through as raw JSON would hide a construct the migrated Java definition
cannot express — surfacing them as an explicit gap makes the migrating team decide consciously.

| Construct | Sheet(s) | Gap category | Notes |
|---|---|---|---|
| SearchAlias | `SearchAlias` | `UNSUPPORTED_SHEET` | No `SearchAlias` generator. |
| UserProfile | `UserProfile` | `UNSUPPORTED_SHEET` | Per-user default worklists; no generator. Populated in most real fixtures, so its removal from passthrough is what forces those definitions to `--allow-gaps` (or a hand-authored UserProfile). **Maintainer decision 2026-07-16, kept after investigation**: the sheet's payoff — pre-selecting a caseworker's workbasket filter — is functionally dead in current XUI (`ProfileService.get()` fetches it but no `case-list.component.ts`/toolkit code path consumes `profile.default.workbasket`); the rows are per-user, per-environment deployment data rather than case-type model (upstream `ccd-definition-processor` already treats `UserProfile.json` as environment-varying, excludable config); and fixture rows carry real staff/contractor emails (e.g. `nigel.dunne@solirius.com`, council addresses in fpl) that should not be baked into a shared Java definition. The comparator's `USER_PROFILE_EXCLUDED` rule drops the sheet from both sides so it stops recurring as a residual, but conversion still hard-fails on it as above. |
| AccessType / AccessTypeRole | `AccessType`, `AccessTypeRole` | `UNSUPPORTED_SHEET` | Org group-access config; no generator (and NOT deprecated — importer + data store consume them). |
| CaseRoles `JurisdictionID` (mixed usage only) | `CaseRoles` | `UNSUPPORTED_VALUE` | `emitCaseRoleJurisdiction()` is all-or-nothing; when every row carries `JurisdictionID` the switch emits it natively, but *mixed* usage (only some rows) cannot be expressed and fails. |
| Unknown / custom `FieldType` (+`FieldTypeParameter`) | `CaseField` | `UNSUPPORTED_VALUE` | A type with no Java carrier that is **not** a real `FieldType` enum constant can only be inferred as `String`→`Text`. `CaseHistoryViewer`/`WaysToPay`/`JudicialUser`/… are completed `FieldType` constants taking the `@CCD(typeOverride)` Java path; a genuinely unknown type fails. Add it as a `FieldType` constant or model it as a complex type to convert it faithfully. |

## The generation-time environment gate

`@CCD(gate = "[!]ENV_VAR:value")` declares that a `CaseData` field is part of the generated
definition only when the predicate matches at `generateCCDConfig` time — the SDK counterpart of a
per-environment overlay fragment (a field that exists only in one environment's spreadsheet).
Grammar and resolution (`System.getProperty` first, then env; case-insensitive value; `!` negation)
are identical to the converter's `OverlayCondition`, evaluated in
[`EnvironmentGate`](../sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/EnvironmentGate.java).
A gated-off field behaves exactly as `@CCD(ignore = true)` — no `CaseField`, authorisation,
event-placement, tab or search rows, and complex types reachable only through it emit nothing — while
the Java member still exists, so typed-getter placements compile. Empty gate (the default) always
matches, so ungated definitions regenerate byte-identically. The converter emits an overlay-only
`CaseField` row whose suffix has a configured `--overlay-suffix` predicate as a real gated member
(base rows win; complementary suffix pairs dedupe to one member). Pinned by
`GatedFieldGenerationTest` (both gate polarities snapshotted) and the minimal golden fixture's
nonprod-only field in both round-trip environments.

### Overlay fragments the SDK has no per-row switch for

The gate is a per-*field* mechanism, and several sheets have no per-row environment switch in the SDK
at all: role-to-access-profile mappings, state authorisations, search-party definitions and per-field
event placements are emitted from static configuration. A definition that splits those across
mutually-exclusive overlay fragments (sscs's `-nonWA` against `-WA-nonprod`) is therefore reproducible
only by admitting the fragment the build being converted would actually have used — admitting both
halves produces a definition wrong in *every* environment, because the two collide and the loser's
rows survive as grants and placements a real build never emits. `OverlayResolver.isActiveRow` applies
that rule (true for a base row, and for a suffixed row whose predicate is active in the convert-time
environment) across the seven row loops that need it, and the new repeatable `--env KEY=VALUE` option
names the target environment: the values are applied as system properties, which `OverlayCondition`
reads before the real environment. Without `--env` those predicates are judged against the ambient
environment.

A separate read-time filter sits upstream of all of this. A row key that names no CCD column never
reaches an imported definition — `json2xlsx` builds each spreadsheet row as
`headers.map(key => record[key])` against the sheet's header row in `ccd-template.xlsx`, so a key
matching no header contributes no cell and is gone before the xlsx exists. `ColumnVocabulary` (in the
reader) decides which keys those are, matching the importer's own `ColumnName` vocabulary
case-insensitively as `equalsColumnNameOrAlias` does, and `JsonDefinitionReader` drops them. Real
definitions carry two shapes: inline documentation the authors know CCD ignores (`Comment`, civil's
`_Comment`/`_Category`/`_Definition`), dropped silently, and typos they do not — sscs's
`",ShowSummaryChangeOption"` (a stray comma swallowed into the key), fpl's `FieldShownCondition` and
`"FieldShowCondition:"`, civil's `PageShowShowCondition` and `retainHiddenValues` — reported as
advisory gaps, since a key one edit from a real column is a finding worth handing back to the team:
the definition plainly meant it and has silently not had it.

## Remaining residual tails

Seven real fixtures convert, compile and round-trip end-to-end, and each is an **enabled**
`RoundTripTest` case that gates its residuals against a checked-in baseline under
`sdk/ccd-definition-converter/src/test/resources/roundtrip-baselines/<fixture>.txt`. The baseline
file *is* the enumerated, reviewed list of that fixture's open gaps; the test passes iff the observed
residuals equal the baseline exactly (a new diff fails as a regression, a vanished diff fails
demanding a baseline refresh — the ratchet only tightens). Current baseline sizes:

| Fixture  | Residual lines | Led by |
|----------|---------------:|---|
| ia       |              1 | one `SearchCriteria`/`OtherCaseReference` row |
| probate  |              6 | five `[STATE]` `CaseEventToFields` no-match rows, plus one `MaNDATORY` typo |
| ET       |             10 | two `retainHiddenValue`, `UnavailabilityDateRange` ×2, `sendNotificationCollection` ×4, two `SearchCriteria` `LiveTo=` rows |
| fpl      |              7 | `caseProgressionReport` pre-states, `colleaguesToNotify` `Label`, `allocationDecision ShowSummaryContentOption`, one `colleagues` `EventToComplexTypes` row, two `HearingVenue LiveTo`, `RepresentativeRole`/`LA_BARRISTER` |
| sscs     |             11 | three `TextArea`-vs-`Text` `FieldTypeParameter`, two `confidentialityRequiredChangedDate` no-match, four `JudicialUser`-vs-`Text`, three `FL_selectWhoReviewsCase` |
| prl      |             43 | `CaseField` 20 (16 of them `ShowSummaryContentOption`), `AuthorisationCaseEvent` 6, `FixedLists`/`CaseEventToFields` 4 each |
| civil    |             89 | `ComplexTypes` 36 (`Label` 11, `CaseFieldID` 10, `CaseEventID` 8), `CaseField` 13, `CaseEventToFields` 12, `CaseTypeTab` 9, `FixedLists` 8 |

Total **167** (measured 2026-08-11 from the checked-in baseline files). These are the *generate*-mode fixtures — the converter emits a fresh model and the
round-trip compares that against the input. Retrofit mode (annotating a team's **existing** model) is
measured separately and is much further from zero; see
[Retrofit-lane residual](#retrofit-lane-residual).

To regenerate a baseline after an intended change, run `GenerateGoldenFiles` with
`-Djunit.jupiter.conditions.deactivate='*'`.

The categories, all SDK-structural limitations or fixture-data findings (none are converter bugs):

- **Complex-type members that are themselves overlay-only** (ET: `UnavailabilityDateRange`,
  `sendNotificationCollection`) — `@CCD(gate)` gates a `CaseData` field; a shared complex class
  would need per-member gates. Routed to passthrough today; imperfect when the gate is on.
- **`CaseField`-sheet `ShowSummaryContentOption=Y`** (fpl, prl): a `CaseField`-sheet flag distinct
  from the numeric `CaseEventToFields.ShowSummaryContentOption` column (which is emitted as Java);
  it has no SDK API and stays a residual.
Three shapes that look like they belong here are **not** residuals: an orphan (unreachable) complex
type or fixed list and a redundant redeclaration of an SDK-predefined type are dropped as accepted
semantic differences (`ORPHAN_COMPLEX_TYPE`/`ORPHAN_FIXED_LIST`/
`PREDEFINED_COMPLEX_TYPE_REDECLARATION`); an illegal-ID complex type or fixed list is generated as
Java via the `@ComplexType(name)` carrier (prl's `schoolDirections&Details`); and platform types such
as `JudicialUser` are real `FieldType` constants emitting `typeOverride` rather than `Text`.

## Retrofit-lane residual {#retrofit-lane-residual}

Everything above measures **generate** mode: the converter emits a fresh model, so it controls every
Java name and type and the round-trip reaches single-digit residuals. **Retrofit** mode annotates a
service's *existing* model, which it does not control — the team's classes have their own names,
kinds, field types and Lombok idioms — so its residual is a different, larger number, measured by a
different harness (`bin/retrofit-verify`, one lane per service, diffing the definition against
`generateCCDConfig` output from the patched model inside the service's own build).

The lane residual is **not** ratcheted in CI (the lanes need a service checkout and a
`publishToMavenLocal`); it is measured by hand and recorded here.

### Per-lane residual (measured 2026-08-11)

| Lane | Case type | Residual | Leading sheets |
|---|---|---:|---|
| sscs | `Benefit` | **0** | — |
| probate | `GrantOfRepresentation` | 58 | `FixedLists` 33, `ComplexTypes` 17, `CaseEventToFields` 6, `CaseField` 2 |
| et | `ET_EnglandWales` | 146 | `ComplexTypes` 137 (`Bundle` 21, `HearingDetails` 17, `BundleDetails` 17, `FlagDetailType` 16, `UpdateReferralType` 13), `FixedLists` 3, `SearchCriteria`/`CaseField`/`CaseEventToFields` 2 each |
| civil | `CIVIL` | 539 | `CaseField` 153, `FixedLists` 196, `ComplexTypes` 148, `CaseEventToFields` 12, `AuthorisationCaseType` 10, `CaseTypeTab` 9 |
| fpl | `CARE_SUPERVISION_EPO` | 569 | `FixedLists` 436 (`HearingVenue` **405**, `CMOStatus` 6, `YesNo` 4), `ComplexTypes` 64, `CaseField` 36, `AuthorisationCaseField` 17, `EventToComplexTypes` 14 |
| prl | `PRLAPPS` | 1,477 | `AuthorisationCaseField` **782** (every one an expected row with no match), `ComplexTypes` 287, `CaseField` 257, `FixedLists` 135, `AuthorisationCaseEvent` 6 |
| **total** | | **2,789** | |

`sscs` is the only lane at zero: `bin/retrofit-verify` reports `SEMANTICALLY EQUIVALENT`, i.e. a real
service's own model, annotated by the patch and built in its own repo, regenerates its hand-written
definition exactly. That is the proof that retrofit mode can be *complete* for a lane rather than
merely close, and it is the lane that carried the shared-jar and abstract-hierarchy problems.

### What remains, per lane

Four of the six are single- or few-shape tails rather than scattered column divergence.

- **probate 58** — the 33 `FixedLists` lines are types that are **jar-resident**, outside the patched
  source tree, so no annotation can reach them (`HandoffReasonId`/`handoffReasonFixedList` lead), plus
  `ComplexTypes` 17.
- **et 146** — `ComplexTypes` 137 is the **same-members-two-IDs** problem across five types: the model
  reaches a class the definition addresses under a different ID (`printHearingDetails` is declared
  `ListingData` where the definition types it `ListingType`, with `AdhocReportType` beneath it;
  `DocumentType` against the definition's `DocumentUpload`, a near-superset adding `creationDate`/
  `ownerDocument`/`tornadoEmbeddedPdfUrl`). Neither ID is an SDK `FieldType` constant, so
  `@CCD(typeOverride)` cannot express it. Awaiting a rename-versus-accept decision per type.
- **fpl 569** — `HearingVenue` **405** is one reference-data list: 405 venue codes the definition holds
  and no enum does. The rest is `ComplexTypes` 64 and `CaseField` 36.
- **prl 1,477** — `AuthorisationCaseField` **782** is a *single* category (an expected grant the
  regenerated definition does not produce at all) and the largest remaining block in any lane, then
  `ComplexTypes` 287 and the unresolvable dotted-path tail.
- **civil 539** decomposes by *shape* rather than by sheet, so the buckets below cut across the sheet
  counts above and omit a handful of one- and two-line tails:

  | Lines | Shape | Cause |
  |---:|---|---|
  | ~101 | `MultiSelectList` → `Collection` (89 `CaseField`, 12 `ComplexTypes`) | `CaseFieldGenerator.resolveCollectionType` infers `MultiSelectList` only for a `Set` of enums; civil models the same CCD construct as `List<Enum>` (`List<MediationDocumentsType>`, `List<ConfirmListingTickBox>`, the `*Toggle` families) |
  | 165 | `FixedLists` row-level (62 expected-only, 103 actual-only) | Declared-versus-referenced binding — but **not** a casing problem: of 36 distinct IDs only three overlap case-insensitively (`claimtypeunspec`, `courtstaffnextsteps`, `paymentfrequency`), so these are mostly genuinely different lists. Expected-only leaders `ClaimTypeUnSpec` 9, `paginationStyle` 7, `GAHearingDurationGAspec` 6; actual-only leaders `TranslatedDocumentType` 19, `DocumentType` 16, `CosRecipientServeLocationType` 12 |
  | ~40 | Divergently-named complex types | The `*LRspec`/`*GAspec` suffix families (`RequestedCourtLocationLRspec`→`RequestedCourt` 4, `HearingSupportRequirements`→`HearingSupport` 4, `FixedRecoverableCostsIntermediate`→`FixedRecoverableCosts` 4, `UserDetails`→`IdamUserDetails` 3, …) — expressible via the existing `@ComplexType(name)` pin |
  | 31 | `ComplexTypes` no-match rows | `GAHearingDetailsGAspec` 11, `Bundle` 9, `BundleFolder` 4, `UploadDocumentOnly` 2, `BundleSubfolder` 2; plus 7 unexpected (`CaseLocationCivil` 5, `HomeDetails` 2) |
  | 26 | `FixedLists` `ListElement` | Downstream of the binding shape above |
  | 10 | `Number` → `BigDecimal` (6 + 4) | `resolveSimpleType`'s numeric case covers `int`/`float`/`double`/`Integer`/`Float`/`Double`/`Long`/`long` — `BigDecimal` is absent, so it falls through to the type's own name |
  | ~55 | `ComplexTypes` column tails | `Searchable` 13, `ElementLabel` 13, `Label` 11, `CaseFieldID` 10, `FieldTypeParameter` 9, `CaseEventID` 8, `FieldShowCondition` 7, `DisplayContext` 3 |
  | 34 | Small sheet tails | `CaseTypeTab` `UserRoles` 7 / `Searchable` 2; `AuthorisationCaseType` 5 no-match + 5 `CRUD`; `CaseEventToFields` 5 `PageShowCondition` + 5 `DisplayContext`; `CaseField` 5 `ElementLabel` + 5 `CRUD`; `RoleToAccessProfiles` 3 (all `CaseAccessCategories=SPEC_CLAIM`); `SearchCriteria` 2 |

### The SDK forms retrofit depends on

Each is narrow, default-off and pinned by a test. They exist because a retrofitted model is real code
and raises questions a hand-written one never does.

- **`@CCD` is `@Repeatable` and legal on a class**, where `member = "<inherited field name>"` says
  which inherited member it configures. A field declared once on a shared superclass is one Java
  member but several CCD members — it emits a row under every complex type that reaches it, and a
  hand-written definition is free to give those rows different metadata or omit some (sscs's abstract
  `Entity` declares `identity`/`name`/`address`/`contact`/`organisation` for `Appellant`, `Appointee`,
  `OtherParty`, `Representative` and `JointParty` alike, and the definition puts a
  `FieldShowCondition` on `representative`'s five rows only). Placed on the subclass the annotation
  **replaces** the inherited field's own `@CCD` wherever rows are produced through that class (its
  `ComplexTypes` members, its `CaseField` rows when reached `@JsonUnwrapped`, and the access those
  rows derive), leaving every other subclass on the field's own declaration; `ignore = true` in this
  form drops the member from that class alone. It also settles a per-subclass
  `typeParameterOverride`, which a single inherited field cannot carry twice (sscs parameterises the
  inherited `documentType` as `documentTypeWelsh` on one subclass and `documentTypeDwp` on another).
  Naming a field the class declares itself, or one no supertype declares, fails generation rather
  than silently doing nothing.
- **A static field is not case data.** A static belongs to the class, not to a case, and Jackson never
  serialises one, so the exclusion `collectGatedOffFieldIds` already applied is applied where the rows
  are produced. sscs's Lombok `@Slf4j` loggers would otherwise emit `ComplexTypes` rows of
  `FieldType=Logger`, and `CorrespondenceDetails`' private `DateTimeFormatter` one of
  `FieldType=DateTimeFormatter` — types no definition can name.
- **A field a subclass redeclares emits one row.** A redeclared field *hides* the superclass one: Java
  resolves the name to the subclass's field and Jackson sees a single property, so only the
  most-derived declaration is case data, and the first declaration `ReflectionUtils.doWithFields`
  reports (it walks subclass-first) is the one kept. sscs's `JointParty` redeclares the inherited
  `id`/`identity`/`name`/`address`/`contact` purely to give each a `@JsonProperty("jointParty…")` ID.
- **A field ID colliding with an unwrapped container's *name* keeps its grants.** CCD field IDs and
  Java member names are independent namespaces a real model collides: sscs declares
  `@JsonUnwrapped private CaseOutcome caseOutcome`, whose prefix-less leaf therefore has CCD ID
  `caseOutcome`. The suppression itself must stay (an unwrapped container emits no `CaseField` row, so
  a placement registering it by name would reference a field that does not exist), so
  `FieldUtils.isUnwrappedContainerId` tests both halves — *names an unwrapped member* **and** *is not
  itself an emitted field ID* — over a `caseFieldIds()` walk mirroring `CaseFieldGenerator`'s prefix
  accumulation, hoisted out of the per-row loop.
- **`@CCD(displayContextParameter)`** carries the column on a *member*, where no builder exists.
  `ComplexFieldTypeParser` reads `DisplayContextParameter` on the `ComplexTypes` sheet, so the date
  format a definition puts on `appellant.confidentialityRequiredConfirmedDate` is expressible only
  there. (On the `CaseField` sheet the importer ignores it, so setting it is harmless but inert.)
- **`@CCD(typeParameterClass)`** names the class declaring the type `typeParameterOverride` spells,
  when no field in the model *declares* it. `typeParameterOverride` writes only the
  `FieldTypeParameter` **column**, while the `FixedLists`/`ComplexTypes` **rows** come from
  `ConfigResolver.resolve`'s walk over the types `CaseData` declares — so a leaf field carrying only
  the override references a list nothing generates. That is exactly the shape reference data takes
  (sscs really spells it `private String hearingEpimsId`, with 160-odd venue codes loaded at runtime
  by `VenueService`); retyping the field would change every caller and every serialised payload in a
  published jar. `typeParameterClass` makes the class reachable exactly as a declared field type is,
  resolved *alongside* the declared type so a `Collection<X>` field can do both, and reaches nothing
  for a field the definition excludes. It is also how an oversized enum's field points at the
  generated companion instead.
- **`complexMemberNoSummary`** completes the placement API: a clustered leaf sits inside its holder's
  member scope and needs the `COMPLEX` row *without* opening one, and `displayContextToMethod` had no
  `COMPLEX` case, so every such row regenerated as `DisplayContext=OPTIONAL`. probate and et both ship
  `COMPLEX` rows with `ShowSummaryChangeOption=N`, hence the `*NoSummary` sibling.
- **`@CCD(ignore)` on a `State` enum constant** drops the constant's `State` row *and* its
  `AuthorisationCaseState` rows (a grant on a state that does not exist fails to import). A service
  reusing an existing enum has constants no case type declares — an `@JsonEnumDefaultValue UNKNOWN`
  sentinel, a legacy composite state — which cannot be deleted because its own code still switches on
  them.
- **A fixed list is emitted for the lists the definition *references*, not for every enum reflection
  reaches.** `getTypes()` is a *reachability* set — an enum enters it as soon as some field anywhere
  **declares** it — and reachability is not the same question as whether the definition has a list. A
  field is free to declare an enum and then declare itself to be something else, which sscs does
  250-odd times (`@CCD(typeOverride = FieldType.Text) private DirectionType directionType`; the
  definition really does type that column `Text`). `CaseFieldGenerator.referencedTypeParameters`
  unions the `FieldTypeParameter` of every emitted row — case fields, members of every complex type,
  fields placed explicitly on events — and `FixedListGenerator` emits a list only when its ID is in
  that union. Reading the emitted rows rather than re-deriving type logic means it cannot disagree
  with what the definition says. It is per-field-then-unioned, not `typeOverride`-alone: sscs's
  `postponementEvent` is a genuine `FixedList` of `eventType`, so that enum survives on the strength
  of that one field even though ten others carry it as `Text`. Two subtleties: a
  `@ComplexType(generate = false)` type must still be *walked* (its members still reference their
  lists — fpl's `StandardDirectionOrder`-named `Order.orderStatus` is the only reference to
  `OrderStatus`), and an enum's own instance fields must be skipped (fpl's `RepresentativeRole`
  carries `Type` and `Set<CaseRole>` in its constructor, which no field references).
- **A complex type reachable only through ignored fields emits nothing.** `ConfigResolver.resolve`'s
  field predicate filters `isFieldIgnored`, which subsumes `isFieldGatedOff` — so `@CCD(gate)`'s
  promise ("excluded from complex-type member emission and from complex-type reachability") holds for
  `ignore`/`@JsonIgnore` too, matching the `CaseField`/`AuthorisationCaseField`/`CaseEventToFields`
  sheets, which already drop those fields. A type reached by an ignored field *and* a live one is
  unaffected (`IgnoredReachGenerationTest` pins all three polarities).
- **A generic field resolves against its declaring class, not to its erasure.** On a field whose
  declared type is a type *variable*, `field.getType()` yields the variable's bound, never the type
  argument the subclass supplied — which breaks the definition in both directions at once: the bound
  becomes reachable in its own right and emits members under an ID no definition row names, while each
  type argument becomes reachable nowhere and emits no rows at all. sscs's
  `AbstractDocument<D extends AbstractDocumentDetails>` holding a `private D value` is the shape.
  Resolving the field against its declaring class — what the collection branch beside it already did —
  closes it; `ResolvableType.resolve()` returns null for a variable no implementation class binds, so
  a genuinely raw or wildcard use still falls back to the erasure.
- **Two reachable classes mapping to one CCD ID merge deterministically.** The same simple name in
  different packages (prl has two `DocumentDetails`) or the same `@ComplexType(name)` merge into one
  output file, first writer winning. The reachable-type map is insertion-ordered
  (`ComplexTypeGenerator` collects into a `LinkedHashMap` rather than losing that order through
  `Collectors.toMap`) and `AddMissingPreferringLabels` lets a real label displace the `" "`
  placeholder from either side, so the outcome does not depend on iteration order at all; two real
  labels still disagree rather than silently merging (`ConfigResolverOrderTest`, `JsonUtilsTest`).

**Known SDK gaps the lanes have surfaced and that are not yet closed** — `resolveCollectionType`
infers `MultiSelectList` only for a `Set` of enums, not a `List` (civil ~101 lines), and
`resolveSimpleType`'s numeric case omits `BigDecimal` (civil 10 lines).

### The patch side: how the model is annotated

The patch never reads a value off the team's own accessors; every display value is copied from the
definition, which is by construction the string the round-trip must reproduce. Guessing among several
plausible accessors would write a wrong value into the definition silently.

- **`RetrofitTypeBinder` binds an ID by *declaration*, not by name.** Real definitions name their
  types independently of the classes behind them — probate's `ExecutorApplying` is
  `AdditionalExecutorApplying`, et's `ClaimantIndividual` is `ClaimantIndType`, fpl's
  `CafcassEnglandOffices` is `EnglandOffices` — and each miss costs twice over: the ID emits an orphan
  companion nothing references, *and* the real class emits a full set of rows under an ID the
  definition never mentions. A `CaseField` or `ComplexTypes` member row whose `FieldTypeParameter` (or
  `FieldType`) is the ID names a field, and that field's declared Java type is the class CCD addresses
  the members on; the ID is pinned onto it as `@ComplexType(name = <id>, generate = true)`, so no
  field declaration is rewritten and no caller in a published jar can break. It resolves to a
  **fixpoint** (a member row is readable only once its owning type has a class, so a nested chain
  binds one level per pass) and refuses any binding that is not unambiguous: a name-based binding
  already exists, referencing fields disagree, the class's own simple name is itself a definition ID,
  two IDs claim one class, an ID whose target another ID already names case-insensitively, or the kind
  mismatches the generator that emits the type (an enum for `FixedLists`; a **class** — not an
  interface, not a record — for `ComplexTypes`). A same-simple-name tie is settled from the declared
  type of the definition's own referencing field, installed before binding because `complexTypeClass`
  is the lookup `bind` itself asks (prl has eight such pairs, a `models.complextypes` class beside a
  `models.dto.cafcass` one). A `FieldTypeParameter` on a row whose `FieldType` is itself a complex
  type is a column CCD never reads (`FieldTypeParser` re-reads it only when the base type is
  `Collection`), so a vestigial value there must not fabricate a candidate.

  > The pin changes the wire-visible `ComplexTypes`/`FixedLists` ID a class emits under. That is the
  > point — it makes it match the definition — but a team that serialises the class elsewhere under
  > its Java name should check per lane before adopting the patch.

- **`ListElement` labels are pinned per enum constant.** `FixedListGenerator` resolves a constant's
  label through one contract — `HasLabel.getLabel()`, then `@CCD(label)`, then `@CCD(hint)`, then the
  constant itself. **Not one enum** across the lanes implements `HasLabel`, yet 430 of them carry a
  display label by some other means (prl's `getDisplayedValue()` behind a `@JsonValue`, fpl's
  `getLabel(Language)`, a bare `label`/`value`/`description` constructor field), so every such list
  emitted `ListElement == ListElementCode`. `@CCD` carries no `@Target` and `FixedListGenerator`
  already reads it off the constant's field, so the patch needs no SDK change. Two source shapes have
  to be handled: a row is matched on the raw `ListElementCode` as well as on the sanitised constant
  name (prl writes the code verbatim, civil upper-snakes it), and a source line shared by several
  constants is split to one per line first, since `@CCD` is not repeatable on a field. Anything the
  split cannot reproduce byte-for-byte (a constant with a body, an interleaved comment) is refused —
  an unpinned constant costs one residual line, a mangled one breaks the team's build. A constant
  already carrying a `@CCD`, one whose definition label already equals its name, or an enum
  implementing `HasLabel` is left alone.
- **A `@JsonValue` enum's labels are keyed on the argument position that provably carries the codes.**
  Such an enum emits a constructor field rather than the constant name, so nothing about a constant's
  *name* says which definition row it carries (`SendToFirstTierActions` names `DECISION_REMADE` and
  emits `remade`). A position qualifies only when its string literals map constants to codes
  one-to-one and cover every row; two qualifying positions that disagree refuse the enum. Nothing is
  inferred from a parameter name or an ordinal.
- **A `@JsonProperty` on a constant redirects the emitted `ListElementCode`**, so an enum spelling its
  codes in the team's house style can back the definition's list. This one is **not** runtime-neutral:
  it changes how the type serialises everywhere. The value comes from the definition, which is what
  that CCD column already carries on the wire, so the redirect aligns the Java type with its own data
  — it is still a change to a published contract and is reported as one. Refused wherever no pin can
  work: a `@JsonValue` anywhere on the enum (it beats a constant's `@JsonProperty`), a definition code
  with no constant, two codes claiming one constant, a constant already pinned elsewhere.
- **A list the enum *almost* covers gets its missing constant synthesised.** The definition holds the
  code and the label and the generator derives the list from the constant set, so faithfully
  reproducing a fifteen-row list from a fourteen-constant enum *is* a fifteenth constant. The
  constructor call copies the argument *shape* of the constants already there — same count, all
  literals — so it compiles for the same reason its siblings do. What each position means is decided
  by evidence from the enum's own constants: a position is the code when every constant with a
  definition row passes its own code there, and the label when most pass their own label. The bars
  differ deliberately — a code is machine-exact, so one dissenter disqualifies the position, while a
  label is prose a team copied and copies drift. A position no rule claims is refused rather than
  filled with a guess. A constant is also refused when one already there passes this row's label,
  which separates a genuine gap (sscs's `ScannedDocumentType` really lacks
  `otherPartyHearingPreferences`) from a value the enum merely spells differently.
- **`State` display columns are pinned per constant.** `StateGenerator` resolves all three off `@CCD`
  on the constant — `label()` → `Name` (falling back to the state ID), `description()` →
  `Description` (falling back to the resolved `Name`), `hint()` → `TitleDisplay` (omitted when empty)
  — and a team's own State enum carries none of them (sscs spells its display names in a separate
  lookup, civil's live only in the definition). `RetrofitStateLabels` bridges CCD state ID → Java
  constant through `StateEnumAnalyser.stateIdToConstant`, the same derivation the emitted config
  references its constants through, so the two cannot disagree about which constant a state is (sscs
  writes `APPEAL_CREATED("appealCreated")` behind a `@JsonValue toString()`). A State enum is
  frequently *also* reachable as a declared field type, so this pass and the `FixedLists` label pass
  want the same constant and `@CCD` is not repeatable on a field; they share one per-constant claim
  and this pass takes it, since the `State` sheet's three columns are always compared whereas a
  definition-less fixed list is an unexpected row whichever `ListElement` it carries.
- **`RetrofitReachableTypes` mirrors `ConfigResolver.resolve`** so the patch sees the same class set
  the generator will (declared field types plus collection element types through one generic wrapper,
  up the `extends` chain, path-guarded, `@JsonUnwrapped` holders descended into but not recorded), and
  gives each reachable class no definition ID binds a **name-less**
  `@ComplexType(generate = false)`. The SDK's walk reaches more classes than the definition declares
  IDs for, in two shapes: a team's own copy of a type the store knows natively (sscs's `DocumentLink`,
  `DynamicList`, `CaseLink`) and the `{id, value}` envelope of a collection, which CCD leaves
  implicit. sscs generated 127 `ComplexTypes` against 118 declared IDs.

### The lane harness

[`bin/verify-all-lanes.sh`](../sdk/ccd-definition-converter/bin/verify-all-lanes.sh) runs the full
five-stage pipeline per lane and **parses** the `LANES` table out of `regen-review-clones.sh` rather
than copying it, so a lane's model repo, definition dirs, overlays, env and type hints cannot drift
between the two scripts. Only the first lane publishes the SDK to mavenLocal. `ia` is deliberately
absent: it has no typed model to annotate (map-based `CaseData`), so its measure is the generate-mode
round-trip.

Three wiring requirements the harness has to get right, each of which silently falsifies a lane's
number when it does not:

- **The lane boots a generated application, not the service's.** `ApplicationEmitter` emits
  `ConverterGeneratedApplication` with a **filtered** component scan —
  `@ComponentScan(basePackages = <model package>, useDefaultFilters = false, includeFilters = @Filter(ASSIGNABLE_TYPE, CCDConfig.class))`
  beside the generator's own package — and the init script sets
  `spring.main.web-application-type=none`. Booting the service's own context instead instantiates the
  service's `@Component`s, which demand beans the lane has no reason to create (civil's
  `InterlocutoryJudgementDocMapper` needs a `DeadlineExtensionCalculatorService`) and the lane fails
  at stage 4 rather than producing a number. `regen-review-clones.sh` passes `--emit-application` and
  `refresh-migration-branches.sh` derives `ccd.rootPackage` from the emitted class's own package
  rather than trusting the hand-written value.
- **Overlay predicates must be single-quoted into `--args=`.** `printf '%q'` is wrong for Gradle's
  `--args=` string: bash escapes `!` as `\!` for history-expansion safety and Gradle forwards the
  backslash verbatim, so `!CCD_DEF_PUBLISH:Y` reaches picocli as `\!CCD_DEF_PUBLISH:Y` and
  `OverlayCondition.parse` reads it as a **non**-negated predicate on an env var literally named
  `\!CCD_DEF_PUBLISH` — putting every negated overlay fragment behind a permanently false guard.
  Single-quoting is inert to bash's history rules and survives Gradle's splitter unchanged.
- **A lane's overlay-suffix set must match the in-JVM fixture's exactly.** A suffix the lane does not
  declare reads as a base row, so mutually-exclusive halves both survive, collide last-wins in the
  `UserRole` enum's `caseTypePermissions`, and produce lines the in-JVM baseline never shows. sscs
  needs eight entries because `extractOverlayTags` takes the **longest** configured suffix — without
  `GS-WA-nonprod`, that file matches plain `nonprod` and loses its WA condition. `CCD_DEF_PUBLISH` is
  the right variable to key them on rather than one of our own invention: `create-xlsx.sh:128-136`
  derives both from its `WA_ENABLED` argument in one branch (WA on sets `CCD_DEF_PUBLISH=Y` and
  excludes `*-nonWA*`; WA off sets `N` and excludes `*-WA-*`), and prod additionally forces `N` at
  `:140`, so `CCD_DEF_PUBLISH:Y` implies nonprod and one predicate expresses the exclusion exactly.

## What the round-trip does not prove

The proof is narrower than "the seven fixtures round-trip byte-clean" — know its limits before
trusting it:

- **The fixtures gate CI against a baseline, not against zero.** All seven real fixtures are enabled
  `RoundTripTest` cases in the `roundTripTest` task (the round-trip GitHub workflow initialises every
  fixture submodule and runs them), alongside the bundled golden fixtures (`minimal` in both env
  polarities, `clustered` in nonprod, which must round-trip *clean*) and the `GatedField` gate-polarity
  snapshots. But a real fixture passes when its residuals equal its checked-in baseline — so what is
  gated is "no *new* diffs and no *silently-vanished* ones", not "no diffs". **The baseline contents
  are the enumerated open gaps**: everything in `roundtrip-baselines/<fixture>.txt` is a known,
  unabsorbed difference this proof explicitly does not close (see
  [Remaining residual tails](#remaining-residual-tails) for the per-fixture counts and categories). A
  fixture whose submodule is not initialised skips (JUnit `Assumptions`) rather than fails, so a
  checkout without submodules still builds; on a submodule-less run that fixture is simply unverified.
- **A finite env matrix per fixture.** Each fixture runs the harness once with a fixed env map, not a
  cartesian product. Only `minimal` exercises both `CCD_DEF_ENV` polarities; every real fixture runs
  `nonprod` only. `CCD_DEF_SHUTTERED:true` (fpl, probate) and `CCD_DEF_PUBLISH=Y` (sscs, probate) are
  **never exercised** — only the flag-unset branch runs. The opposite overlay polarities are unverified.
- **Overlay-predicate bugs can be masked.** The expected side is built from the same IR with the same
  `OverlayCondition`/env map that drives the converter, so a predicate that filters both sides
  identically would still pass.
- **Callback URL values ARE proven; callback runtime behaviour is out of converter scope.** The
  converter emits no SDK callback wiring — every callback column (`CallBackURL*` and its
  `RetriesTimeout*` on `CaseEvent`, `CallBackURLMidEvent`/`RetriesTimeout*MidEvent` on
  `CaseEventToFields`) is carried through the passthrough graft verbatim, `${CCD_DEF_*}` placeholders
  and all, and compared **exactly** like any other column. So the regenerated definition points at the
  original service's own endpoints, byte-for-byte — the migrated service keeps serving them unchanged
  and the round-trip proves the URL values are preserved. What the diff still does not (and cannot)
  assert is that a callback fires correctly over HTTP at runtime; that is a property of the running
  service, not of the definition, and is unchanged by the conversion.
- **Ordering and layout are not proven**: on-screen display order on any sheet (see accepted difference
  3), row order within a sheet, and per-file splits / filenames.
- **Passthrough constructs are reproduced, not proven equal.** `PassthroughMerger` is additive — it can
  only add a row, never detect the generator wrongly emitting a conflicting one it can't override.

## Reading the conversion reports

A conversion run (via `--report-dir`) writes:

- **`gap-report.md`** — human-readable table of every construct the converter could not express
  directly in Java: sheet/row/column, action taken (`PASSTHROUGH_ROW`, `PASSTHROUGH_COLUMN`,
  `CONDITIONAL_CODE`, `ADVISORY`, or `OMITTED_FAIL`), and why. An `ADVISORY` entry is non-blocking —
  it flags either a redundant input declaration (an orphan or predefined-type redeclaration) that
  produces no output and is safe to delete, or a display-only over-grant the SDK injects and a
  comparator rule forgives (the `AuthorisationCaseField` injected-read records — the dominant
  `ADVISORY` category by count, see
  [§ AuthorisationCaseField injected-read records](#authorisationcasefield-injected-read-records)).
  No `ADVISORY` entry corresponds to a passed-through row.
- **`gap-report.json`** — the same findings as structured data (`entries` plus `summary` counts),
  for tooling.

(There is no `callback-map.json`: the converter does not rewrite callbacks, so there is nothing to
map. Every original callback URL is carried through verbatim in the passthrough content below and
reproduced byte-for-byte in the regenerated definition.)

Passthrough content lands under `--passthrough-dir` (`base/<relativePath>` for unconditional
sheets, `<overlay-suffix>/<relativePath>` for environment-gated ones, indexed by `manifest.json`);
`PassthroughMerger` merges it back additively after `generateCCDConfig` — it never removes or
overrides a generator-emitted row, which is why the two accepted differences above cannot be
corrected by passthrough.
