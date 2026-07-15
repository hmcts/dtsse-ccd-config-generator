# Passthrough Reduction Plan

> **Status: the (G) and small (F) items below are IMPLEMENTED** (2026-07-15). Nine SDK features
> were added and wired into the converter, retiring the Banner, AuthorisationComplexType, State
> Description, SignificantEvent, EnableForDeletion, Shuttered, CaseRoles JurisdictionID, unregistered
> RoleToAccessProfiles and search-extras passthroughs/grafts, plus the per-field `Publish`/`PublishAs`
> cascade (`PUBLISH_CASCADE` comparator rule retired). Per-item status is in the **Recommendation**
> column and the notes under the decision table. Remaining (P)/large-(F) items stay passthrough.
>
> This began as a read-only analysis of every row in the "Constructs carried by passthrough" table in
> [`json-conversion-fidelity.md`](json-conversion-fidelity.md). It recommended, per construct,
> one of:
>
> - **(G) Generate via EXISTING SDK API** — the converter should emit builder calls instead of passthrough.
> - **(F) Add SDK FEATURE** — a new builder API worth having.
> - **(I) Comparator IGNORE** — provably non-functional; move to the ignore list.
> - **(P) KEEP passthrough** — genuinely the right mechanism.
>
> Impact = observed row counts across the seven real round-trip fixtures (ia, sscs, fpl, ET, civil,
> prl, probate). Counted directly from the fixture JSON on 2026-07-15. Where a fixture stores a
> sheet as a fragment directory the fragment rows are summed.

## Fixture row counts (the impact denominator)

Per-sheet totals across all seven fixtures, most-used first (constructs that map 1:1 to a sheet):

| Sheet | ia | sscs | fpl | ET | civil | prl | probate | **Total** |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| CaseEventToFields | 1950 | 735 | 1121 | 1203 | 2275 | 5431 | 4542 | **17257** |
| CaseEventToComplexTypes | 258 | 746 | 1898 | 0 | 2510 | 6433 | 318 | **12163** |
| AuthorisationComplexType | 10 | 0 | 359 | 233 | 357 | 145 | 21 | **1125** |
| RoleToAccessProfiles | 48 | 61 | 70 | 64 | 50 | 203 | 27 | **523** |
| UserProfile | 0 | 2 | 22 | 0 | 3 | 1 | 38 | **66** |
| CaseRoles | 2 | 1 | 33 | 15 | 7 | 29 | 2 | **89** |
| Banner | 1 | 0 | 1 | 0 | 1 | 0 | 1 | **4** |
| AccessType / AccessTypeRole | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| SearchAlias | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |

Derived counts (rows that actually go through a given passthrough path, not the whole sheet):

| Passthrough path | Rows | Source |
|---|--:|---|
| Per-field `CaseEventToFields` metadata graft (FieldShowCondition/Label/Hint/RetainHiddenValue/DisplayContextParameter/DefaultValue) | 10459 | rows carrying ≥1 of those columns |
| `CaseEvent` callback-URL columns (`CallBackURL*`) | 1555 | rows with a CallBackURL* column |
| `CaseEvent` `RetriesTimeout*` columns | 742 | rows with a RetriesTimeout* column |
| Mid-event callback graft on `CaseEventToFields` | 1066 | rows with a *MidEvent column |
| State `Description` ≠ `Name` | 80 | State rows |
| Search/workbasket rows with `ListElementCode` | 68 | 41+11+12+4 across the four field sheets |
| Search/workbasket `FieldShowCondition` grafts | 48 | WorkBasketInputFields |
| `CaseRoles JurisdictionID` set | 55 | CaseRoles rows |
| Conditional / multi-target `PostConditionState` (`;` or `(...)`) | 40 | ia 6, ET 17, prl 17 |
| Search/workbasket `ResultsOrdering` grafts | 14 | SearchResultFields 2, WorkBasketResultFields 9, SearchCasesResultFields 3 |
| `SignificantEvent` set | 10 | CaseEvent rows |
| `Shuttered` set (Jurisdiction) | 6 | Jurisdiction rows |
| `EnableForDeletion` set (CaseType) | 3 | CaseType rows |
| Unknown/custom `FieldType` (JudicialUser 15, CaseHistoryViewer 8, HearingData 13, …) | ~dozens | CaseField rows |

(`PostConditionState=*` wildcard collapse — 1355 rows — is **not** a passthrough; it is absorbed by
the `POST_CONDITION_NO_CHANGE`/`DEFAULTS` comparator rules. Listed here only to keep it distinct
from the 40 genuinely conditional/multi rows below.)

---

## Decision table

Ranked by actionable impact. High-row constructs that are *correctly* passthrough today (deliberate
design, or zero fixtures) are ranked lower because there is nothing worth changing.

| # | Construct | Today | Evidence summary | Recommendation | Effort | Impact (rows) |
|--:|---|---|---|---|---|--:|
| 1 | **Per-field `CaseEventToFields` metadata** | column-graft | The SDK's `CaseEventToFieldsGenerator.applyMetadata` already emits **every** grafted column (FieldShowCondition, CaseEventFieldLabel/Hint, RetainHiddenValue, DisplayContextParameter, DefaultValue), and `FieldCollection` has field(...) overloads carrying all of them. The converter grafts them instead of threading them through the overloads. | **G — DONE (partial)**: label/hint/`FieldShowCondition`/`DisplayContextParameter` emitted via the Optional/Mandatory summary overloads; `DefaultValue` (typed to the field's `Value`) and `RetainHiddenValue` (no combinable overload) and the READONLY/HIDDEN/*NoSummary contexts keep the additive graft. | M–L | **10 459** |
| 2 | **AuthorisationComplexType** | row (per shape) | Generator + `grantComplexType` API both exist and emit flat `{CaseTypeID,CaseFieldID,ListElementCode,UserRole,CRUD}`. The importer only ever reads that flat shape; nested `AccessControl[]`/`UserRoles[]` are a processor build-time expansion that fires on **all** sheets. The "different shape" reason is true only pre-flattening. The comparator's `AccessControlExpansionRule` *deliberately excludes* this one sheet. | **G — DONE**: emit `grantComplexType` (split into helper classes), un-excluded the sheet from `AccessControlExpansionRule`; unresolvable/overlay rows stay passthrough. | M | **1 125** |
| 3 | **EventToComplexTypes** | row (per event/field) | Generator exists (`CaseEventToComplexTypesGenerator`, fed by `.complex(getter).mandatory/optional/readonly(member,…)`), but observed rows carry columns with **no** builder equivalent (per-member SecurityClassification, RetainHiddenValue, Publish/PublishAs, explicit FieldDisplayOrder, inline FieldType/FieldTypeParameter redefinition, ElementLabel). Converter emits empty `.complex().done()` blocks only. | **P** (keep; partial **F** possible for the label/hint/showCondition subset) | L (F) | **12 163** |
| 4 | **Callback URLs + `RetriesTimeout*`** (CaseEvent) | column-graft | Deliberate design: converter emits **no** SDK callback wiring so the env-specific `${CCD_DEF_*}` URLs round-trip byte-for-byte and the migrated service keeps serving its own endpoints. SDK *can* emit callbacks but would recompute from `callbackHost`. | **P** (deliberate, correct) | — | 1 555 + 742 |
| 5 | **Mid-event callback URL + `RetriesTimeout*MidEvent`** (CaseEventToFields) | column-graft | Same deliberate design as #4, per-page. | **P** (deliberate, correct) | — | 1 066 |
| 6 | **Unregistered `RoleToAccessProfiles`** | row | `caseRoleToAccessProfile` takes a `UserRole` constant; org/IDAM roles that carry only an access-profile mapping can't be registered without emitting an invalid empty-CRUD `AuthorisationCaseType` row. 380 of 523 rows are non-bracketed (org-style) names — an upper bound on the candidates (exact passthrough count needs a conversion run). | **F — DONE**: `builder.roleToAccessProfile(String)` emits the mapping row without registering a `UserRole`/`AuthorisationCaseType`. | M | ≤ ~380 |
| 7 | **State `Description` ≠ `Name`** | overwrite-graft | SDK hardcodes `Description=Name`; no `state(...).description()` builder. | **F — DONE**: `@CCD(description)` on the State enum constant. | S | **80** |
| 8 | **Search/workbasket `ListElementCode` rows** | row | `SearchBuilder`/`SearchCasesBuilder` `field(...)` overloads take no `ListElementCode` (search within a complex sub-field). | **F — DONE** (four flat sheets via the per-field lambda; `SearchCasesResultFields` keeps passthrough). | S–M | **68** |
| 9 | **Search/workbasket `FieldShowCondition`/`ResultsOrdering`** | column-graft | Same builders carry neither column; `SearchCasesResultFields` grafts `FieldShowCondition` only. | **F — DONE** (per-field lambda `showCondition`/`resultsOrdering`; `SearchCasesResultFields` `ResultsOrdering` stays a residual). | M | 48 + 14 |
| 10 | **`CaseRoles JurisdictionID`** | column-graft | `CaseRoleGenerator` emits only ID/Name/Description; but the generator **knows** the config's jurisdiction — it just doesn't write it. | **G/F — DONE**: `builder.emitCaseRoleJurisdiction()` (all-or-nothing; mixed usage keeps the graft + a gap). | S | **55** |
| 11 | **Conditional / multi-target `PostConditionState`** | overwrite-graft | Runtime-honoured (see claim 5). `EventBuilder` holds `Set<S> postState` but `CaseEventGenerator` collapses size≠1 → `*`; no priority/condition API. | **P** (functional; not ignorable) | (F=L) | **40** |
| 12 | **`SignificantEvent`** (CaseEvent) | column-graft | No `EventBuilder` method for the significant-event flag. | **F — DONE**: `event…significant()`. | S | **10** |
| 13 | **`Shuttered`** (Jurisdiction) | column-graft | No `jurisdiction(...)` shuttered-flag builder. | **F — DONE**: `builder.jurisdictionShuttered()`. | S | **6** |
| 14 | **Banner** | whole-sheet | Importer: exactly one banner per jurisdiction, 4 columns (Enabled/Description/Url/UrlText). Clean, bounded, 4/7 fixtures. | **F — DONE**: `builder.banner(enabled, description, url, urlText)`. | S | **4** |
| 15 | **`EnableForDeletion`** (CaseType) | column-graft | Fixed CaseType column set in `JSONConfigGenerator`; no builder. | **F — DONE**: `builder.enableForDeletion()`. | S | **3** |
| 16 | **Unknown / custom `FieldType`** (CaseField) | overwrite-graft | Platform types with no Java carrier (JudicialUser, CaseHistoryViewer, HearingData…). WaysToPay/ComponentLauncher/OrganisationPolicy/Flags already exist as SDK types. | **P** (long tail); **F** for common recurring ones | M (F) | ~dozens |
| 17 | **Orphan / illegal-ID / predefined ComplexTypes** | row | A declared-but-unreachable / non-Java-identifier / SDK-predefined re-declaration cannot be a Java `@ComplexType`. | **P** (structural) | — | few |
| 18 | **Orphan-path FixedLists** | row | A FixedList reachable only through an unreachable complex type generates no enum. | **P** (structural) | — | few |
| 19 | **UserProfile** | whole-sheet | Per-user default worklists keyed on `UserIDAMId` (real user emails) — environment/test data, not case-type model. Importer requires the sheet but the content is not model config. | **P** (env data, not model) | — | 66 |
| 20 | **AccessType / AccessTypeRole** | whole-sheet | Organisation/MyHMCTS group case-access config, runtime-consumed by data-store (feature-flag gated). Not deprecated (newly added). Zero fixtures use it. | **P** (functional; zero usage) | — | 0 |
| 21 | **SearchAlias** | whole-sheet | Alias→field-path for ES indexing. Zero fixtures use it. | **P** (zero usage) | — | 0 |

**Nothing is recommended for (I) comparator-ignore** — every construct examined is either
runtime-functional or a deliberate/structural passthrough. No provably-vestigial construct was found
(the one candidate, conditional `PostConditionState`, is honoured at runtime — see claim 5).

**Outcome.** #2 (G) and the small (F) cluster (#6, #7, #8, #9, #10, #12, #13, #14, #15) plus the
per-field `Publish`/`PublishAs` cascade are **implemented** — the Banner, AuthorisationComplexType,
State Description, SignificantEvent, EnableForDeletion, Shuttered, CaseRoles JurisdictionID,
unregistered RoleToAccessProfiles and search-extras passthroughs/grafts are gone, and
`PUBLISH_CASCADE` is retired. #1 (G) is **partially** implemented: label/hint/`FieldShowCondition`/
`DisplayContextParameter` now emit via the Optional/Mandatory summary overloads, while `DefaultValue`
(typed to the field's `Value`) and `RetainHiddenValue` (no combinable overload) and the
READONLY/HIDDEN/*NoSummary contexts keep the additive graft. #3 (EventToComplexTypes) stays
passthrough (largest, genuinely structural). The deliberate callback passthroughs (#4, #5) stay as-is.
All seven fixture round-trips held or improved (sscs 66→62); the golden fixtures and the full gate
suite are green.

---

## The five specific-claim verdicts

### Claim 1 — "AuthorisationComplexType definitely gets created by the generator." — **AGREE (⇒ recommend G)**

The generator exists and the builder API exists:

- `ConfigBuilder.grantComplexType(TypedPropertyGetter field, String listElementCode, Set<Permission> permissions, R... roles)` —
  `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/ConfigBuilder.java:109`.
- `AuthorizationComplexTypeGenerator.toJson` emits exactly
  `{CaseTypeID, CaseFieldID, ListElementCode, UserRole, CRUD}` (one row per role) and merges on
  `("CaseTypeID","CaseFieldID","ListElementCode","UserRole")` —
  `sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/AuthorizationComplexTypeGenerator.java:36-51`.

**Is the earlier "structurally different shape" justification true?** Only at the raw-JSON level.
The fixtures carry three shapes (fpl: 298 `nested` `AccessControl[]`, 8 `array` `UserRoles[]`, 53 flat
`role`; ia/ET/probate: flat only; civil/prl: mix). But **the importer only ever parses a flat
per-role row**: `AuthorisationComplexTypeParser` reads a single scalar `AccessProfile` (alias
`UserRole`) and `CRUD` per row and builds one `ComplexFieldACLEntity` each — it has no handling of
`AccessControl[]`/`UserRoles[]`
(`apps/ccd/ccd-definition-store-api/.../parser/AuthorisationComplexTypeParser.java:61-90`). The array
shapes are flattened at build time by the processor's `access-control-transformer.js`, which fires on
data **shape** across *all* sheets — including AuthorisationComplexType — and then renames
`UserRole`→`AccessProfile`
(`apps/ccd/ccd-definition-processor/src/main/lib/access-control-transformer.js:29-84`, applied at
`json2xlsx.js:49`).

So `grantComplexType` can reproduce the real fixtures' rows exactly *at import semantics*: fpl's 359
rows become one `grantComplexType(field, listElementCode, perms, role…)` call per (field,
listElementCode, role) — the SDK's flat output equals what the processor produces from fpl's
nested/array input. **The one thing standing in the way is the comparator, not the SDK**: the
`ACCESS_CONTROL_EXPANSION` rule *explicitly skips* AuthorisationComplexType
(`sdk/ccd-config-generator/src/testFixtures/java/uk/gov/hmcts/ccd/sdk/diff/AccessControlExpansionRule.java:59-64`),
so the nested input is compared verbatim against the SDK's flat output and won't match. **Recommendation:
emit `grantComplexType` calls and extend `AccessControlExpansionRule` to flatten this sheet too** (the
same expansion it already does for `AuthorisationCaseField/Type/Event`). Effort M; retires 1 125 rows.

### Claim 2 — "EventToComplexTypes is also generated." — **PARTLY TRUE; full emission NOT achievable ⇒ recommend KEEP passthrough**

The generator exists: `CaseEventToComplexTypesGenerator.write/expand` walks each event's
`FieldCollection.getComplexFields()` (the `.complex(getter)…` override path) and emits
CaseEventID/CaseFieldID/ListElementCode/DisplayContext/EventElementLabel/EventHintText/FieldDisplayOrder/DefaultValue
(`sdk/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/generator/CaseEventToComplexTypesGenerator.java:26-98`).
`FieldCollection` has the overloads to feed it (showCondition/label/hint/defaultValue/displayContextParameter —
`.../api/FieldCollection.java:76-261`).

**Why the converter passthroughs the whole sheet:** its `EventsConfigEmitter` emits complex fields
only as *empty* `fields.complex(getter).done()` blocks
(`sdk/ccd-definition-converter/.../emit/config/EventsConfigEmitter.java:604-612`) — it does not
reconstruct the per-member override calls. `DefaultDefinitionLinker.buildEventToComplexTypesPassthrough`
buckets the raw rows by (overlay-suffix, event, field) into per-file fragments keyed on
`(CaseEventID, CaseFieldID, ListElementCode)` and passes them through raw
(`sdk/ccd-definition-converter/.../link/DefaultDefinitionLinker.java` — `buildEventToComplexTypesPassthrough`).
That per-(event,field) fragment split is the fix for what the prl `@Disabled` note is about: without
it, members appearing under multiple events/fragments collide on key. (The ~164 conflicting
`ElementLabel` lines in prl's residual are a **ComplexTypes** fixture-data issue, not
EventToComplexTypes.)

**Is full builder-call emission achievable?** No. The 12 163 observed rows carry columns the
`.complex()` path has **no** API for: per-member `SecurityClassification`, `RetainHiddenValue`,
`Publish`/`PublishAs`, explicit `FieldDisplayOrder`, inline `FieldType`/`FieldTypeParameter`
member-type redefinition, and standalone `ElementLabel` (distinct from `EventElementLabel`) — all
present in the fixture column-set census. The subset that *is* expressible (DisplayContext + label +
hint + showCondition + defaultValue) could be emitted as a partial **(F)**, but it would only shrink,
not remove, the passthrough and adds fragile nested-getter resolution. Given it is the single largest
passthrough and genuinely structural, **keep passthrough**; treat richer `.complex()` member overrides
as a future L-effort SDK feature.

### Claim 3 — "AccessType may be deprecated in favour of AccessProfile; the processor just re-writes it all." — **REFUTE on both counts**

- **Not the AccessProfile rename.** AccessType/AccessTypeRole are **organisation / MyHMCTS group
  case-access** config — columns `OrganisationProfileID`, `OrganisationalRoleName`, `GroupRoleName`,
  `CaseAssignedRoleField`, `GroupAccessEnabled`, `CaseAccessGroupIDTemplate`
  (`apps/ccd/ccd-definition-store-api/.../parser/AccessTypesParser.java:88-100`,
  `AccessTypeRolesParser.java:93-107`; entities `AccessTypeEntity`/`AccessTypeRoleEntity`). The
  `UserRole→AccessProfile` rename lives on an entirely separate sheet/parser/entity —
  `RoleToAccessProfilesParser` (sheet `RoleToAccessProfiles`). Do not conflate them.
- **Not deprecated — the opposite.** git-blame on the parser: commit `c726d1c4b`
  *"GA-133 Add a new Tab AccessType and AccessTypeRole in the DefinitionStore to replace
  AccessTypeRoles Tab"* — these are **newer** sheets that *replaced* a legacy `AccessTypeRoles` tab.
- **Importer still consumes them**, feature-flag gated: `ImportServiceImpl.parseAccessTypeRoles`
  only runs when `applicationParams.isCaseGroupAccessFilteringEnabled()`
  (`.../service/ImportServiceImpl.java:409-435`).
- **Consumed at runtime by data-store**, not just the importer: `CaseAccessGroupUtils` reads
  `caseTypeDefinition.getAccessTypeRoleDefinitions()` to build case-access groups on both write paths
  (`apps/ccd/ccd-data-store-api/.../service/common/CaseAccessGroupUtils.java:53-59`, called from
  `CreateCaseEventService.java:267` and `SubmitCaseTransaction.java:161`).
- **The processor does NOT rewrite them.** It has *no* sheet definition for either — the strings
  appear nowhere in the repo, and its json2xlsx path asserts `Unexpected spreadsheet data file` for any
  JSON whose basename isn't a template sheet
  (`apps/ccd/ccd-definition-processor/src/main/lib/ccd-spreadsheet-utils.js:45-58`). So it would
  *reject*, not rewrite, these.

**Verdict:** functional, runtime-consumed, not deprecated; zero fixture usage ⇒ **KEEP passthrough**
(an SDK feature isn't worth it until a service needs it in Java).

### Claim 4 — Banner (+ SearchAlias, UserProfile) as candidates to ADD to the generator

- **Banner** — **F, effort S.** Importer `BannerParser` enforces exactly one banner per jurisdiction,
  columns `BannerDescription`/`BannerEnabled`/`BannerURL`/`BannerURLText`
  (`apps/ccd/ccd-definition-store-api/.../parser/BannerParser.java:23-36`). Observed shape is exactly
  those four (Enabled `Yes`/`No`/`false`, optional Url/UrlText). 4/7 fixtures, 1 row each.
  Sketch: `builder.banner(boolean enabled, String description, String url, String urlText)` (or a small
  `BannerBuilder`) → a `BannerGenerator` writing the four columns. Cheapest clean win in the table.
- **SearchAlias** — **P.** Importer `SearchAliasFieldParser` maps `SearchAliasID`→`CaseFieldID` path
  for ES indexing (type derived by walking the path;
  `.../parser/SearchAliasFieldParser.java:36-104`). Zero fixture usage ⇒ not worth an API now
  (could be F-low-priority: `searchAlias(id, CaseData::getField)`).
- **UserProfile** — **P.** `UserProfilesParser` requires the sheet and reads
  `UserIDAMId`/`WorkBasketDefault{Jurisdiction,CaseType,State}`
  (`.../parser/UserProfilesParser.java:24-47`), producing `WorkBasketUserDefault` objects pushed to
  `ccd-user-profile-api`. But the rows are **per-user environment/test data keyed on real email
  addresses** (e.g. `damian@swansea.gov.uk`), not case-type model — hard-coding them in Java config
  would be an anti-pattern. Keep passthrough. 66 rows.

### Claim 5 — "Multiple / conditional post states don't really do anything." — **REFUTE (fully honoured at runtime ⇒ recommend KEEP passthrough, NOT ignore)**

Conditional multi post-states are evaluated at runtime with priority + JEXL conditions:

- `CasePostStateService.evaluateCaseState` prioritises then evaluates
  (`apps/ccd/ccd-data-store-api/.../service/common/CasePostStateService.java:24-29`).
- `PrioritiseEnablingCondition.prioritiseEventPostStates` sorts ascending by `priority`
  (`.../enablingcondition/PrioritiseEnablingCondition.java:12-24`).
- `CasePostStateEvaluationService.evaluatePostStateCondition` walks the priority-sorted list, evaluates
  each non-default `enablingCondition` via JEXL, returns the **first match**, else the default
  (`.../service/common/CasePostStateEvaluationService.java:21-43`;
  `JexlEnablingConditionParser.evaluate` `.../enablingcondition/jexl/JexlEnablingConditionParser.java:53-66`).
- `*` = keep current state: `CreateCaseEventService.updateCaseState` only changes state when
  `shouldChangeState(postState)` is true, and that is `!equalsIgnoreCase(CaseStateDefinition.ANY, postState)`
  with `ANY = "*"` (`CreateCaseEventService.java:499-504,554-556`; `CaseStateDefinition.java:15`).
- Also evaluated on case creation (`DefaultCreateCaseOperation.java:201`).

The importer parses the full grammar: `EventPostStateParser` splits multiple states on `;`, matches
conditional entries against `POST_STATE_CONDITION_PATTERN = ".*[\(.*\)]:\d{1,2}"`
(`state(condition):priority`), stores each as an `EventPostStateEntity` with `priority` +
`enablingCondition` (`apps/ccd/ccd-definition-store-api/.../parser/EventPostStateParser.java:16-101`;
entity `.../entity/EventPostStateEntity.java:15-35`).

The SDK cannot express it: `EventBuilder` holds a `Set<S> postState`
(`.../api/Event.java:30,218-219`) but `CaseEventGenerator.getPostStateString` collapses any set of
size ≠ 1 to `*` and otherwise emits the single state
(`.../generator/CaseEventGenerator.java:106-110`) — no priority, no condition, no multi-state set.

**Verdict:** 40 fixture rows (ia 6, ET 17, prl 17) are genuinely behavioural. **Keep passthrough**;
do **not** move to the comparator ignore list.

---

## Method notes

- Row counts were taken from the seven fixtures listed in `RoundTripTest` on 2026-07-15, summing
  fragment-directory rows where a sheet is split. Counts are of *fixture* rows, not of gap-report
  `PASSTHROUGH_*` entries; for the two paths where the passthrough subset ≠ the whole sheet
  (`RoleToAccessProfiles` unregistered rows, unknown `FieldType`) the exact passthrough count needs a
  conversion run and is given as an upper bound.
- The converter sources were read while another agent was editing them, so line numbers in
  `DefaultDefinitionLinker`/`EventsConfigEmitter` may have drifted; method names are stable references.
- SDK, importer, processor and data-store citations are `path:line` against the repos as checked out
  on 2026-07-15.
