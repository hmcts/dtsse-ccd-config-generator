# JSON → Java Conversion: Round-Trip Fidelity

`ccd-definition-converter` converts a hand-written JSON CCD definition into config-generator
Java. The `round-trip` test suite (`RoundTripTest`) is the correctness proof: it converts a real
definition to Java, compiles it, runs the SDK's `generateCCDConfig`, and semantically diffs the
regenerated definition against the original input.

**Every construct the SDK can express round-trips byte-identically, modulo the enumerated gaps on
this page.** Each gap is classified one of four ways:

- **Not semantic** — a provably-equivalent spelling difference; the importer treats both forms
  identically. Forgiven by a named comparator rule (each rule class carries the justification in
  javadoc plus absorb-and-still-fails tests).
- **Semantic, accepted** — the regenerated definition genuinely differs, in a stated, bounded way
  the maintainer has accepted as permanent.
- **Fixed with passthrough** — the SDK has no API for the construct, so the converter carries the
  input JSON through verbatim (`PassthroughMerger` after generation). These round-trip **exactly** —
  the "gap" is only that the construct lives as JSON, not Java.
- **Open** — unabsorbed residual diffs on the real fixtures; not accepted, each a named
  SDK-structural limitation or fixture-data finding awaiting a decision or fix.

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
| `Yes`/`true` ≡ `Y` etc. on boolean columns | Not semantic | `YN_CANON` | Incl. `SignificantEvent`/`EnableForDeletion`/`Shuttered`/`BannerEnabled` |
| Injected `caseHistory` field/tab/auth rows (creates/widens `CRU`) | **Semantic, accepted** | `CASE_HISTORY` | CaseHistoryViewer carries no submitted data; grants are display-only |
| Injected/widened read (`R`) on unrestricted-tab fields | **Semantic, accepted** | `TAB_READ_INJECTION` | Roles already event-granted gain tab-field visibility |
| Surplus `⊆ {C,R}` grants on `Label`/`READONLY` fields | **Semantic, accepted** | `IMMUTABLE_FIELD_CR` | Display permission on data-free fields |
| Uniform vestigial `AuthorisationCaseState LiveTo` (probate's `01/01/2020` on every row) | **Semantic, accepted** | `LIVE_TO_VESTIGIAL` | Dead sheet-wide end-of-life the SDK can't emit; per-row divergent `LiveTo` still fails ([detail](#3-uniform-vestigial-authorisationcasestate-liveto)) |
| Display-order renumbering (`*DisplayOrder`/`PageColumnNumber` never compared, any sheet) | **Semantic, accepted** | `DEFAULTS` + `CASE_TYPE_TAB` strips | Relative order preserved by row order (FixedLists actively sorted); `PageColumnNumber=2` flattened |
| SearchAlias sheet | Fixed with passthrough | whole-sheet | No SDK generator |
| UserProfile sheet | Fixed with passthrough | whole-sheet | Per-user default worklists; no generator |
| AccessType / AccessTypeRole sheets | Fixed with passthrough | whole-sheet | Org group-access config; no generator (and NOT deprecated — importer + data store consume them) |
| EventToComplexTypes rows | Fixed with passthrough | row | Per-member event display overrides have no `EventBuilder` equivalent (12,163 rows — largest passthrough) |
| `SearchCasesResultFields` complex-leaf rows + `FieldShowCondition` | Fixed with passthrough | row / column-graft | Its generator hardcodes `UserRole`/`UseCase`; the four flat search sheets emit these natively now |
| Orphan / illegal-ID / predefined ComplexTypes; orphan-path FixedLists | Fixed with passthrough | row | Unreachable or non-Java-representable declarations |
| Conditional / multi-target `PostConditionState` | Fixed with passthrough | overwrite-graft | Runtime honours `state(cond):priority` (JEXL, first-match-wins); `EventBuilder` models one post-state |
| Callback URLs + retries (all phases, incl. mid-event) | Fixed with passthrough | column-graft | Deliberate: no SDK callback wiring emitted; input URLs carried byte-exactly, `${CCD_DEF_*}` included, and compared exactly |
| `CaseEventToFields` `DefaultValue`/`RetainHiddenValue` (+ metadata for non-summary contexts) | Fixed with passthrough | column-graft | `DefaultValue` is typed to the field's value; no combinable overload for the rest |
| CaseRoles `JurisdictionID` (mixed usage only) | Fixed with passthrough | column-graft | `emitCaseRoleJurisdiction()` is all-or-nothing; all-rows usage emits natively |
| Unknown / custom `FieldType` (`CaseHistoryViewer`, `JudicialUser`, …) | Fixed with passthrough | overwrite-graft | No Java carrier; original type replaces the `String`-inferred `Text` |
| Overlay-only complex-type *members* (ET) | **Open** | — | Needs per-member `@CCD(gate)`; field-level gates cover everything else |
| No-API columns: `PrintableDocumentsUrl`, `CanSaveDraft`, `ShowSummaryContentOption`, `NullifyByDefault` | **Open** | — | Each needs an SDK API or a decision |
| SDK keyed collisions: `SearchPartyGenerator` name key; `SearchCases` hardcoded `UserRole`/`UseCase` | **Open** | — | `src/main` generator limitations |

Anything not in this table that survives normalisation is a **real fidelity gap** — the round-trip
fails on it. Current open-gap totals per fixture are in
[Remaining residual tails](#remaining-residual-tails).

## Cosmetic normalisation rules

The rules live in
[`sdk/ccd-config-generator/src/testFixtures/java/uk/gov/hmcts/ccd/sdk/diff/`](../sdk/ccd-config-generator/src/testFixtures/java/uk/gov/hmcts/ccd/sdk/diff/)
and are applied by `NormalisingCcdConfigComparator` before a diff is judged a failure. Each rule
class carries the full justification in javadoc plus tests proving it absorbs exactly its shape and
still fails on real drift. Most are genuinely cosmetic; a few (`CASE_HISTORY`,
`TAB_READ_INJECTION`) are really narrowly-scoped *semantic* concessions — flagged in their bullets and
kin to the accepted differences in tier 2. One bullet each:

- **`ACCESS_CONTROL_EXPANSION`** — expands the `UserRoles[]` and `AccessControl[]` array shorthands
  on the `Authorisation*` sheets into the flat per-role rows `ccd-definition-processor` produces at
  build time; both sides are expanded identically. Runs first, whole-definition, so later rules see
  flattened rows. This now includes **`AuthorisationComplexType`**: the converter emits it as flat
  per-role `grantComplexType` rows, so the sheet is flattened like every other `Authorisation*` sheet
  (its former per-sheet exclusion is removed).
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
  CaseHistoryViewer field carries no submitted case data.
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
- **`EMPTY_STRING_ABSENT`** — an empty string or JSON `null` on one side equals an absent column on
  the other (and mutually blank/null columns collapse); the importer treats all identically.
- **`FIELD_TYPE_COMPLEX`** — `FieldType=Complex, FieldTypeParameter=<TypeId>` ≡ `FieldType=<TypeId>`
  — the same complex-type reference, two spellings.
- **`IDENTIFIER_WHITESPACE`** — trims leading/trailing whitespace on identifier columns
  (`ID`/`ListElementCode`/`FieldTypeParameter`/`CaseFieldID`/`CaseEventID`/`CaseStateID`/`TabID`);
  the importer trims identifier cells for cross-sheet lookups. Never touches prose columns.
- **`KEY_ALIAS`** — canonicalises legacy column aliases the importer accepts interchangeably:
  `UserRole`→`AccessProfile`, `Name`→`Label` (CaseField), `CaseTypeId`→`CaseTypeID`.
- **`LIVE_FROM`** — strips the mandatory-but-meaningless `LiveFrom` from both sides. `LiveTo` is
  deliberately left alone — an end-of-life date is behavioural — bar the one narrow accepted
  exception `LIVE_TO_VESTIGIAL` below.
- **`LIVE_TO_VESTIGIAL`** — a **semantic, accepted** concession (not cosmetic; see
  [accepted difference 3](#3-uniform-vestigial-authorisationcasestate-liveto)): forgives a uniform,
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
- **`POST_CONDITION_NO_CHANGE`** — `PostConditionState=*` equals the event's single pre-state;
  "no change" and "ends in that same state" are the same runtime behaviour.
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
- **`TAB_READ_INJECTION`** — a **semantic superset**, not cosmetic: the generator's tab loop injects
  read (`R`) on every field of an unrestricted tab for every already-granted role. The rule *widens*
  an input grant by a surplus `R` and *removes* actual-only `R` rows for roles that already hold
  another grant — so the regenerated definition grants read visibility on tab fields to roles the
  input did not. `AccessClassComputer` records this as non-derivable (`AUTH_NOT_DERIVABLE`). Same
  mechanism as `IMMUTABLE_FIELD_CR` below, scoped to tab-derived reads.
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

## Accepted semantic differences

Permanent, accepted limitations of the SDK's generation model. The first is a genuine value
difference absorbed by a named rule that forgives exactly the accepted superset — never the
reverse shape, which would mask a regression. The second is an entire column family the comparator
excludes from comparison altogether.

> The former per-field `Publish` cascade is no longer an accepted difference. The SDK now carries a
> per-field `publish(boolean)`/`publishAs(String)` API ([PR #1027](https://github.com/hmcts/dtsse-ccd-config-generator/pull/1027),
> merged), so `CaseEventToFieldsGenerator` cascades the event-level flag only where a field sets no
> explicit publish. The converter emits `.publish(false)` on every field a publishing event's input
> did not publish (and `.publishAs(...)` for a rename), reproducing the input's per-field `Publish`
> exactly. The `PUBLISH_CASCADE` rule is retired.

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
end-of-life date cannot hide behind this rule. This absorbed ~92% of probate's former residual (its
baseline fell from 442 lines to 34).

## Constructs carried by passthrough (not expressed in Java)

The converter reproduces these verbatim through `PassthroughMerger` (additive `JsonUtils.mergeInto`
after generation), because the SDK has no API for them. They round-trip exactly — they are not diff
exceptions — but they live as JSON under `--passthrough-dir`, not as Java. Each construct below is one
row (source: `DefaultDefinitionLinker`). *Mechanism* is the merge shape:

- **whole-sheet** — the entire sheet passes through (the generator emits nothing for it).
- **row** — whole rows are added for records the generator omits.
- **column-graft** — only the named columns are grafted, additively, onto a generator-emitted row
  (never overwriting a value the generator computed).
- **overwrite-graft** — the named columns *replace* the generator's value on the matched row (used
  where the SDK writes a forced default the input differs from).

| Construct | Sheet(s) | Mechanism | Why there is no SDK API |
|---|---|---|---|
| SearchAlias | `SearchAlias` | whole-sheet | No `SearchAlias` generator. |
| UserProfile | `UserProfile` | whole-sheet | No `UserProfile` generator (per-user default worklists). |
| AccessType / AccessTypeRole | `AccessType`, `AccessTypeRole` | whole-sheet | No generator for org access-type config. |
| EventToComplexTypes | `CaseEventToComplexTypes` (→ `EventToComplexTypes`) | row (per event/field) | Per-member event display-context overrides have no `EventBuilder` equivalent. |
| `SearchCasesResultFields` `ListElementCode` rows | `SearchCasesResultFields` | row | The `SearchCasesBuilder` generator hardcodes `UserRole=""`/`UseCase=orgcases`; a row searching within a complex sub-field is reproduced verbatim. (The four flat `SearchBuilder` sheets now emit `ListElementCode`/`FieldShowCondition`/`ResultsOrdering` via the per-field lambda — see below.) |
| `SearchCasesResultFields` `FieldShowCondition` | `SearchCasesResultFields` | column-graft | Its generator carries no per-field lambda; `FieldShowCondition` on a non-LEC row is grafted, `ResultsOrdering` stays a documented residual. |
| Orphan / illegal-ID / predefined ComplexTypes | `ComplexTypes` | row | A declared-but-unreachable complex type, one whose ID is not a legal Java identifier, or an explicit re-declaration of an SDK-predefined type is not emitted as a Java `@ComplexType`. |
| Orphan-path FixedLists | `FixedLists` | row | A FixedList reachable only through an unreachable complex type generates no enum. |
| Conditional / multi-target `PostConditionState` | `CaseEvent` | overwrite-graft | `EventBuilder` models a single post-state, so the generator emits only the primary state. |
| Callback URLs (about-to-start / about-to-submit / submitted) + their `RetriesTimeout*` | `CaseEvent` | column-graft | The converter deliberately emits **no** SDK callback wiring, so the generator writes no `CallBackURL*`/`RetriesTimeout*`; the input values (env `${CCD_DEF_*}` placeholders included) are grafted back verbatim. |
| Mid-event callback URL + its `RetriesTimeout*MidEvent` | `CaseEventToFields` | column-graft | Same: mid-event is a per-page property, carried verbatim per field row rather than wired (a bracketed metadata `CaseFieldID` such as `[STATE]` is skipped — the generator emits no row for it to graft onto). |
| `CaseEventToFields` `DefaultValue` / `RetainHiddenValue` (+ label/hint/showCondition/DCP for READONLY, HIDDEN and *NoSummary contexts) | `CaseEventToFields` | column-graft | `DefaultValue` is typed to the field's `Value` (unknown to the converter, so it cannot be passed as a literal); no combinable overload carries `RetainHiddenValue` alongside a DCP; and only the Optional/Mandatory *summary* contexts expose the metadata overload. For those contexts label/hint/`FieldShowCondition`/`DisplayContextParameter` are emitted via the `FieldCollection` overloads; elsewhere they fall back to this additive graft. |
| CaseRoles `JurisdictionID` (mixed usage only) | `CaseRoles` | column-graft | `emitCaseRoleJurisdiction()` is all-or-nothing; when only *some* rows carry `JurisdictionID` the switch cannot be used, so those rows are grafted (a gap records the mixed usage). When every row carries it, the switch is on and nothing is grafted. |
| Unknown / custom `FieldType` (+`FieldTypeParameter`) | `CaseField` | overwrite-graft | A CCD-platform type with no Java carrier (`CaseHistoryViewer`, `JudicialUser`, `WaysToPay`, …) is generated as `String` → `FieldType=Text`; the original type must replace that. |

Anything not expressible as code *or* passthrough is an `OMITTED_FAIL` entry in the gap report and
fails the conversion unless `--allow-gaps`.

### Constructs moved from passthrough to generated Java

The following constructs used to live in this table and are now emitted as real `ConfigBuilder`/
`@CCD` calls (the SDK gained the API for each), so their passthrough/graft routing was deleted:

- **Banner** → `builder.banner(enabled, description, url, urlText)` (`BannerGenerator`); the whole-sheet
  passthrough is gone.
- **AuthorisationComplexType** → `builder.grantComplexType(CaseData::getX, listElementCode, perms, role)`,
  one flat per-role grant, split across `…ComplexTypeGrantsNN` helper classes to keep serializable-lambda
  bytecode under the 64 KB limit. The input's flat / `UserRoles[]` / nested `AccessControl[]` shapes are
  expanded to per-role grants (matching the processor), and `AccessControlExpansionRule` now flattens
  this sheet on both sides. A row whose complex field does not resolve to a plain `CaseData` getter, whose
  role is not a registered `UserRole`, or that is overlay-suffixed stays a residual passthrough.
- **Per-field `Publish`/`PublishAs`** → `.publish(false)`/`.publish(true)`/`.publishAs(...)` on the field
  (`FieldCollection`), reproducing the input's per-field publish exactly; the `PUBLISH_CASCADE` accepted
  difference is retired.
- **State `Description`** (differs from `Name`) → `@CCD(description = …)` on the State enum constant
  (`StateGenerator`); the `STATE_DESCRIPTION` cosmetic rule still forgives a `Description` that repeats
  `Name`.
- **`SignificantEvent`** → `event…significant()` (`EventBuilder`).
- **`EnableForDeletion`** → `builder.enableForDeletion()`; **`Shuttered`** → `builder.jurisdictionShuttered()`
  (both `JSONConfigGenerator`). `DEFAULTS` forgives an explicit `N`/`false` where the generator omits the
  column (it writes them only when true).
- **CaseRoles `JurisdictionID`** (when *every* row carries it) → `builder.emitCaseRoleJurisdiction()`.
- **Unregistered `RoleToAccessProfiles`** (organisational / IDAM role names) → `builder.roleToAccessProfile(name)`,
  which emits only the mapping row without registering a `UserRole` or an empty-CRUD `AuthorisationCaseType`.
- **Search/workbasket `ListElementCode`/`FieldShowCondition`/`ResultsOrdering`** on the four flat
  `SearchBuilder` sheets → the per-field lambda `field(getter, label, f -> f.listElementCode(…).showCondition(…).resultsOrdering(…).role(…))`.
  Only `SearchCasesResultFields` (whose generator cannot carry these) keeps a passthrough.

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

## Remaining residual tails

Seven real fixtures convert, compile and round-trip end-to-end, and each is now an **enabled**
`RoundTripTest` case that gates its residuals against a checked-in baseline under
`sdk/ccd-definition-converter/src/test/resources/roundtrip-baselines/<fixture>.txt`. The baseline
file *is* the enumerated, reviewed list of that fixture's open gaps; the test passes iff the observed
residuals equal the baseline exactly (a new diff fails as a regression, a vanished diff fails
demanding a baseline refresh — the ratchet only tightens). Current baseline sizes:

| Fixture  | Residual lines |
|----------|---------------:|
| ia       |              5 |
| fpl      |             24 |
| ET       |             34 |
| probate  |             34 |
| sscs     |             62 |
| prl      |            187 |
| civil    |            211 |

(probate fell from 442 to 34 once `LIVE_TO_VESTIGIAL` absorbed its uniform vestigial
`AuthorisationCaseState LiveTo` — ~408 lines; prl fell from 199 to 187 as `CONFLICTING_ELEMENT_LABELS`
collapsed its cross-fragment `ElementLabel` conflicts and Part 3's `CaseField`-DCP strip closed the
rest of that tail. The other fixtures held. To regenerate a baseline after an intended change, run
`GenerateGoldenFiles` with `-Djunit.jupiter.conditions.deactivate='*'`.)

The categories, all SDK-structural limitations or fixture-data findings (none are converter bugs):

- **Complex-type members that are themselves overlay-only** (ET: `UnavailabilityDateRange`,
  `sendNotificationCollection`) — `@CCD(gate)` gates a `CaseData` field; a shared complex class
  would need per-member gates. Routed to passthrough today; imperfect when the gate is on.
- **No SDK builder API**: `CaseType PrintableDocumentsUrl` (fixed CaseType column set in
  `JSONConfigGenerator`), `CanSaveDraft` (probate), `ShowSummaryContentOption`, a
  `DisplayContextParameter` on labelled/non-member tab fields;
  `CaseEventToFields NullifyByDefault` (parsed into the model but emitted by no generator and — unlike
  `DefaultValue`/`RetainHiddenValue`/`FieldShowCondition` — *not* in the column-passthrough graft set,
  so a `NullifyByDefault=Y` surfaces as an unabsorbed diff).
- **Unreachable / non-Java complex types** (prl): whole `ComplexTypes` members with no match in the
  generated definition — orphan/unreachable types and platform types carried as `Text` (e.g.
  `JudicialUser`) — the bulk of prl's remaining tail, routed to passthrough or type-grafted.
- **SDK generator keyed collisions**: `SearchPartyGenerator` keys on `(CaseTypeID, SearchPartyName)`
  so same-named rows differing only in collection field collapse last-wins; the `SearchCases`
  builder emits an empty `UserRole` and hardcodes `UseCase=orgcases`.

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
  `CONDITIONAL_CODE`, or `OMITTED_FAIL`), and why.
- **`gap-report.json`** — the same findings as structured data (`entries` plus `summary` counts),
  for tooling.

(There is no `callback-map.json`: the converter no longer rewrites callbacks, so there is nothing to
map. Every original callback URL is carried through verbatim in the passthrough content below and
reproduced byte-for-byte in the regenerated definition.)

Passthrough content lands under `--passthrough-dir` (`base/<relativePath>` for unconditional
sheets, `<overlay-suffix>/<relativePath>` for environment-gated ones, indexed by `manifest.json`);
`PassthroughMerger` merges it back additively after `generateCCDConfig` — it never removes or
overrides a generator-emitted row, which is why the two accepted differences above cannot be
corrected by passthrough.
