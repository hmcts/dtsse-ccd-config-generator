# Retrofitting Existing Java Models Instead of Generating Fresh Ones

`ccd-definition-converter` today converts a hand-written JSON CCD definition into a **brand-new**
config-generator Java model (`CaseData`, complex types, `State`/`UserRole` enums, config classes).
Migrating teams, however, already own rich, hand-maintained Java domain models that their callbacks
and services are written against. Generating a parallel model forces them either to maintain two
models or to do a big-bang swap of every callback.

This document investigates a **retrofit mode**: instead of emitting a fresh `CaseData`, the converter
would apply the SDK annotations (`@CCD`, `@ComplexType`, `@JsonUnwrapped`) to the team's **existing**
model classes and report the mismatches it cannot resolve. The model their code already uses would
become the definition source of truth.

The verdict up front: **retrofit is feasible for the team-owned-POJO archetypes (FPL, ET, Civil, SSCS),
and even there it is a substantial assisted-migration effort, not a push-button conversion.** Match rates
climb steeply once the model was itself hand-built against the definition: FPL 28 %, SSCS 94 %, Civil 94 %,
ET 98 %. **Civil is the strongest pilot candidate** (in-repo model, reusable `State` enum, generic
`Element<T>`, huge model = high value); ET matches even better but is a published library and uses a
concrete collection wrapper the SDK mis-resolves. **SSCS is also feasible** — its `sscs-common` model is a
published jar, but it is **owned by the SSCS team** and consumed only by their own repos (a coordination
cost like ET's, not a block); its 94 % match rate and directly-reusable `State` enum are strong, offset by
the most hostile collection wrapper of any archetype. Only IA is genuinely off the annotation path
(map-based model shape). Evidence follows.

---

## 1. The three archetypes, measured

### 1a. FPL — POJO in the service's own repo (viable; see ET §1d and Civil §1e for stronger candidates)

Source: `test-builds/fpl-ccd-configuration/service/src/main/java/uk/gov/hmcts/reform/fpl/model/`.
Definition: `test-builds/fpl-ccd-configuration/ccd-definition/CaseField/`.

`CaseData.java` is a 1314-line Jackson/Lombok POJO (`@Data`, `@SuperBuilder`, `@JsonProperty`,
`@JsonUnwrapped`, `@JsonIgnore`). It carries **236 top-level fields** and **30 `@JsonUnwrapped`
event-data sub-objects** (`AllocateJudgeEventData`, `ChildrenEventData`, `ManageOrdersEventData`, …)
whose fields flatten into `CaseData`'s namespace. The model spans **366 `.java` files** with
**1259 distinct property names** (field names ∪ `@JsonProperty` values across all classes).

**Important context: FPL does NOT currently use the SDK.** `build.gradle` has no `hmcts.ccd.sdk`
plugin, there are no `CCDConfig` implementations, and the model carries zero `@CCD`/`@ComplexType`
annotations. FPL hand-writes both its Java model *and* its JSON definition independently. So this is
a genuine greenfield retrofit target, not a model already shaped for the SDK.

**Match-rate measurement.** The definition declares **1711 distinct `CaseField` IDs**. Of these,
**263 are `FieldType: Label`** (presentational — the SDK emits these from event page config, not
from model fields), leaving **1448 data-bearing IDs**. Matching those against the 1259-name model
property union by exact name:

| Bucket | Count | % of 1448 data IDs |
|---|---|---|
| Exact-name match to a model property | **408** | **28 %** |
| Unmatched | 1040 | 72 % |

The 28 % headline is a *floor*, not the achievable rate, because exact-name matching misses several
things the real matcher would resolve, and the unmatched 1040 decompose into distinct, mostly
tractable categories:

- **175 role-variant fields** ending `CTSC` / `LA` / `Removed` / `Resealed`
  (`adultPsychRepParentsListCTSC`, `applicantOtherDocListLA`, …). Verified: these have **no matching
  base field in the model** (`adultPsychRepParentsList` is absent too). They are *separate CCD
  fields* FPL uses to segregate the same collection by access role — a pattern with no 1:1 Java
  field. They need synthesised fields or an explicit access-variant mechanism, not a name match.
- **~44 `…DFJCourt` fields** (`birminghamDFJCourt`, `bristolDFJCourt`, …) — these are effectively
  fixed-list/court-toggle fields, again with no domain field.
- **86 `…List` fields** (dynamic lists) and **27 `…Label`/`…_label`** stragglers that slipped the
  `FieldType=Label` filter — presentational, resolved from event config, not model fields.
- The remainder are true naming divergences the Jackson-faithful matcher (getters, `@JsonProperty`,
  recursive `@JsonUnwrapped` prefix expansion) would recover — a random sample of 30 unmatched IDs
  found the ID string present somewhere in the service source in nearly every case, i.e. the concept
  exists, only the top-level-name link is missing.

**Structural wins that make FPL tractable:**

- **Collections already fit.** FPL wraps collections as `List<Element<X>>`
  (`model/common/Element.java`), whose fields are exactly `UUID id` + `T value`. The SDK's
  `ListValue<T>` (`sdk/.../type/ListValue.java`) is `String id` + `T value`. The SDK detects
  collections **structurally** — `ConfigResolver`/`CaseFieldGenerator` call
  `Collection.class.isAssignableFrom(...)` then extract the *second* generic argument
  (`fieldType.getGeneric(0).getGeneric(0)`); the wrapper class name is never checked. So
  `List<Element<Applicant>>` is understood as a Collection of `Applicant` **without any change to
  `Element`** — the wrapper need not be `ListValue`. (The `id` field type differs, `UUID` vs
  `String`, which is a serialisation detail to verify in round-trip, not a match blocker.)
- **`@JsonUnwrapped` is honoured.** FPL's 30 event-data unwraps map directly onto the SDK's
  `@JsonUnwrapped` prefix-flattening (`CaseFieldGenerator.appendUnwrapped`), which is the same
  mechanism the converter already synthesises via its `FieldClusterer`.
- **`@JsonIgnore` already means ignore.** `FieldUtils.isFieldIgnored` skips a field if it has
  `@JsonIgnore` **or** `@CCD(ignore=true)`. FPL's 69 `@JsonIgnore` fields are already correctly
  excluded — no work needed.

**One decisive conflict — the `State` enum.** FPL *has* a `State` enum, but its constant names do
**not** equal the CCD state IDs. It reconciles them with `@JsonProperty`:

```java
@JsonProperty("PREPARE_FOR_HEARING")
CASE_MANAGEMENT("PREPARE_FOR_HEARING", "Case management"),
@JsonProperty("Open") OPEN("Open"),
@JsonProperty("Gatekeeping") GATEKEEPING("Gatekeeping"),
```

But the SDK's `StateGenerator.write()` derives the state ID from **`enumConstant.toString()`** — the
raw constant name — and **ignores `@JsonProperty` entirely**. Retrofitting FPL's `State` as-is would
emit IDs `CASE_MANAGEMENT`, `OPEN`, `GATEKEEPING`… whereas the definition requires `PREPARE_FOR_HEARING`,
`Open`, `Gatekeeping`. Comparing the enum (9 constants) against the definition's 10 state IDs:
`CASE_MANAGEMENT` vs `PREPARE_FOR_HEARING`, `OPEN` vs `Open`, `GATEKEEPING` vs `Gatekeeping`,
`SUBMITTED` vs `Submitted`, `DELETED` vs `Deleted` all diverge — **at least 5 of 9 states conflict**.
Retrofit must either rename constants (breaks FPL's own persistence, which the code comment explicitly
warns against) or the converter must keep generating a fresh `State` enum in retrofit mode.

**FPL verdict: FEASIBLE as assisted migration.** ~28 % exact matches out of the box; the Jackson-faithful
matcher lifts that materially; but 175 role-variant fields, the `State` enum conflict, and the DFJ/court
fields all need converter-generated supplements or human decisions. Not push-button.

### 1b. SSCS — POJO in the team's own published library (FEASIBLE; published-jar caveat like ET)

Definition: `test-projects/sscs-tribunals-case-api/definitions/benefit/sheets/CaseField/` (four
`CaseField*.json` fragments — `CaseField.json` plus `-nonprod`/`-panelComposition`/`-workAllocation`),
case type `Benefit` — the exact `--input` `RoundTripTest.sscsBenefitRoundTrips` converts
(`RoundTripTest.java:113-114`).
Model: `SscsCaseData` at `sscs-common/src/main/java/uk/gov/hmcts/reform/sscs/ccd/domain/SscsCaseData.java`
(package `uk.gov.hmcts.reform.sscs.ccd.domain`), measured against `hmcts/sscs-common@master` (HEAD
`3a902f5`). `test-projects/sscs-tribunals-case-api/build.gradle:371` pulls it as a **compiled jar**
(`com.github.hmcts:sscs-common:6.3.43`; the fixture's `RoundTripTest` note references `6.3.56`).

`SscsCaseData.java` is a 957-line Jackson/Lombok POJO (`@JsonIgnoreProperties(ignoreUnknown = true)`,
`@Data`, `@Builder(toBuilder = true)`, `@JsonInclude`, `@AllArgsConstructor`, `@NoArgsConstructor`) with
**256 top-level fields**. The reachable model (walking `@JsonUnwrapped` clusters) spans **373 `.java`
files** (233 in the `ccd.domain` package); the Jackson-faithful tree-walk from `SscsCaseData` yields
**517 emittable property IDs**.

**Ownership — the correction. The SSCS team owns `sscs-common`.** `sscs-common` is *not* a foreign
library: it is `apps/sscs/sscs-common`, one of the SSCS team's own repos, published as
`com.github.hmcts:sscs-common` (`apps/sscs/sscs-common/build.gradle:13,122` — `maven-publish`,
`artifactId 'sscs-common'`) and its README opens "A shared library for SSCS projects". Its only workspace
consumers are **other SSCS repos** — `sscs-tribunals-case-api`, `sscs-case-loader`,
`sscs-ccd-case-migration` (each on `sscs-common:6.3.43`). So annotating it is a change the team makes to
its own source; the coordination cost is a version bump propagated across the team's own repos — **exactly
ET's published-jar situation** (`et-shared` consumed by sibling ET services), *not* a cross-team block.
The earlier "unowned library" classification was wrong.

**Match-rate measurement (Benefit).** The four `CaseField*.json` files declare **590 distinct
`CaseField` IDs**; **43 are `FieldType: Label`**, leaving **547 data-bearing IDs**. Matching those against
the model with the Jackson-faithful rules (§2) — field name, `@JsonProperty` override, recursive
`@JsonUnwrapped` prefix expansion, Lombok getters:

| Bucket | Count | % of 547 data IDs |
|---|---|---|
| Exact-name match (tree-walk from `SscsCaseData`) | **512** | **94 %** |
| Unmatched | 35 | 6 % |

Restricting the property union to only the `ccd.domain` package (the `SscsCaseData` tree and its complex
types) gives **513/547 (94 %)** — the match is not an artefact of unrelated classes. This is level with
Civil and just below ET, and far above FPL — because `SscsCaseData` **is** the CCD serialization domain
model, hand-built field-for-field against the Benefit definition (the SDK-faithful walker resolves it
directly; no fuzzy matcher needed). The **35 unmatched** IDs decompose cleanly and none is a naming
divergence — verified by grepping each ID across `sscs-common/src`: **31 of 35 appear nowhere in the
model source at all** (concept absent, not a broken link):

- **14 definition-only complex fields** whose `FieldType` is a custom complex type present only in the
  definition (`onlinePanel`/`panel`, `lapseInfo`/`lapse`, `withdrawalInfo`/`withdrawal`,
  `decisionCorrectionInfo`, `referralInfo`/`otjReferral`, `caseCorrection`, `decisionInfo`,
  `interlocDecisionInfo`/`interlocDirectionInfo`/`interlocStrikeOutInfo`, `judgeAppealNotePad`,
  `dwpNoActionDoc`, `interlocWaiverInfo`, `SearchCriteria`). These have no Java field to annotate — they
  round-trip as untyped JSON handled outside the model, like Civil's ~30 SDO composites; they need a
  synthesised field or generate-fresh supplement.
- **21 event-only / runtime / presentational fields** with standard `FieldType`s
  (`caseHistory`/`CaseHistoryViewer`, `workType`/`timeExtension`/`reasonsForChallenge`/`revisedReason`/
  `dwpResponseState`/`workBasketHearingRoute` fixed lists, `informationFromDWP`/`informationFromOther`/
  `tcwInProgress` YesOrNo event inputs, `directionText`/`bodyContentCorrection` textareas,
  `activeHearingId`/`activeHearingVersionNumber` numbers, `dateInformationRequested`/`restoreCasesDate`,
  `exceptionRecordReference`/`restoreCaseFileName`/`caseName`/`confidentialityTab` text) — transient event
  data or CCD-runtime pseudo-fields emitted from event/UI config, exactly like FPL's `…Label` stragglers
  and ET's launcher pseudo-fields.

**UNMATCHED Java fields (model props with no definition ID): only 5** —
`ccdCaseId` (a `@JsonProperty(access = WRITE_ONLY)` metadata field), `letterAttachedDocuments`,
`adjournCaseNextHearingDateOrTime`, `doesOtherPersonKnowWhereYouLive`, `keepHomeAddressConfidential`.
Each is a candidate for `@CCD(ignore=true)` or is already `@JsonIgnore`d. The model does **not** carry a
sprawl of un-defined fields — it tracks the Benefit definition tightly in both directions.

**Structural finding — the collection wrapper is the WORST of any archetype (worse than ET).** SSCS does
**not** use FPL/Civil's generic `Element<T>` and does not use ET's `List<GenericTypeItem<X>>` generic
form either. It wraps collections almost exclusively as `List<ConcreteWrapper>` where each wrapper is a
**non-generic concrete class** carrying a `value` (and sometimes `id`) field:

- `List<Hearing>` where `Hearing` is `@Value class Hearing { HearingDetails value; }`
  (`Hearing.java`). The definition expects `FieldTypeParameter: hearingDetails`
  (`CaseField.json` `hearings` row) — i.e. the *inner* `HearingDetails`.
- `List<SscsDocument>` / `List<SscsWelshDocument>` / `List<DwpDocument>` where each `extends
  AbstractDocument<D>` (`AbstractDocument.java` = `String id` + `D value`) — concrete subclasses of a
  generic base.
- `List<ElementDisputed>` (×10 fields), `List<Correspondence>`, `List<Bundle>`, `List<CaseLink>` … all
  `@Value`/`@Data` classes with a `value` field and no type parameter.

The SDK detects the collection structurally but only descends into the wrapper when the element
`hasGenerics()`: `CaseFieldGenerator.resolveCollectionElementType` (`CaseFieldGenerator.java:250-262`) does
`elementType = fieldType.getGeneric(0); if (elementType.hasGenerics()) elementType = elementType.getGeneric(0);`.
For `List<Hearing>`, `getGeneric(0)` resolves to `Hearing`, which reports `hasGenerics() == false`, so the
SDK does **not** unwrap `.value` — it would emit `FieldTypeParameter=Hearing` instead of the required
`hearingDetails`. This is the same failure mode as ET's 32 concrete `*TypeItem` fields, but far more
pervasive: across the reachable model, **~120 `List<Concrete>` field-instances (58 distinct concrete
wrapper classes) mis-resolve**, versus only **14 generic instances** (`List<CcdValue<T>>`,
`List<CollectionItem<…>>`) that the `hasGenerics()` descent handles correctly. Nearly every SSCS
collection needs either a per-field `@CCD(typeParameterOverride=…)` or the same SDK change ET's caveat
proposes (walk a concrete `value`-bearing wrapper's superclass/field to find the inner type). **This is
the single biggest technical cost for SSCS** and materially larger than ET's.

**State: directly reusable — the opposite of FPL.** `sscs-common` has a `State` enum
(`ccd/domain/State.java`) with **22 constants** (21 real + `UNKNOWN`). Its constant *names* are ALL-CAPS
(`APPEAL_CREATED`, `WITH_DWP`, …) and do **not** equal the CCD state IDs — but unlike FPL, the enum
overrides `toString()` with **`@JsonValue`** to return the lowercase `id` field (`State.java:41-45`:
`@JsonValue @Override public String toString() { return id; }`). Because `StateGenerator.write` derives
the state ID from **`enumConstant.toString()`** (`StateGenerator.java:26`), retrofitting `State` as-is would
emit `appealCreated`, `withDwp`, … — and **all 21 definition state IDs match those `toString()` values
verbatim** (`State/State.json`); the two extra enum values (`withdrawnRevisedStruckOutLapsedState`,
`unknown`) are simply surplus. So SSCS's enum is **directly reusable with zero conflict** — a decisive
advantage over FPL (which conflicts on ≥5/9), on par with Civil. **One implementation snag, not a
blocker:** `StateGenerator.enumToJsonMap` also calls `enumType.getField(enumConstant.toString())`
(`StateGenerator.java:42`) to read a per-constant `@CCD` label — with `toString()` overridden this looks
up a field named `appealCreated` (the id) rather than `APPEAL_CREATED` (the constant), which throws
`NoSuchFieldException`. Retrofit must switch that lookup to `((Enum<?>) enumConstant).name()` (or catch
and fall back). This affects any team whose `State` enum overrides `toString()` via `@JsonValue` — a
pattern SSCS uses and FPL/Civil do not.

**Other annotations, all SDK-compatible:** `SscsCaseData` uses **23 `@JsonUnwrapped`** sub-objects
(`InternalCaseDocumentData`, `SscsPipCaseData`, `SscsFinalDecisionCaseData`, `SchedulingAndListingFields`,
`WorkAllocationFields`, …) — the **most of any archetype**, all **prefix-less** (flatten directly into
`SscsCaseData`'s namespace), which is exactly the `CaseFieldGenerator.appendUnwrapped` mechanism (and the
recursive walk the matcher performs, §2). **18 `@JsonProperty`** overrides on `SscsCaseData` (e.g.
`dwpUCB`, `dwpPHME`, `isCorDecision`) — all honoured by `FieldUtils.getFieldId`. **47 field-level
`@JsonIgnore`** — already correctly excluded by `FieldUtils.isFieldIgnored`. **178 class-level
`@JsonIgnoreProperties(ignoreUnknown = true)`** across the model — defensive deserialization, harmless to
the SDK (which reflects fields, not this annotation).

**SSCS verdict: FEASIBLE — 94 % match floor, reusable `State`, team-owned published jar.** The ownership
block was a mis-classification: `sscs-common` is the SSCS team's own library, consumed only by their own
repos, so annotating it is in their control (published-jar coordination cost like ET, not a cross-team
block). The match rate (94 %) is level with Civil, the `State` enum is directly reusable (better than
FPL), and the 35 unmatched IDs are almost all genuinely definition-only or event-only. The **decisive
caveat** is the collection wrapper: SSCS's ~120 concrete `List<Wrapper>` fields are the most hostile
`hasGenerics()`-descent case in the whole study — worse than ET's 32 — and would need pervasive per-field
overrides or the same SDK fix ET wants, plus the small `StateGenerator` `toString()`/`name()` fix. Ranks
**below Civil and ET** (its collection problem is larger than ET's and it lacks Civil's clean generic
wrapper), **well above FPL** (much higher floor, reusable `State`).

### 1c. IA — map-based case type (IMPOSSIBLE to retrofit as annotations)

`apps/ia/ia-case-api/.../domain/entities/AsylumCase.java`:

```java
public class AsylumCase extends HashMap<String, Object> implements CaseData {
```

The class body has **zero domain fields** — only an `ObjectMapper` and `read/write/clear/remove`
helpers over the map. There is nothing to annotate. Field identity lives entirely in a companion
enum, `AsylumCaseFieldDefinition` (2948 lines, **869 constants**), each carrying a String CCD id and
a Jackson `TypeReference`:

```java
APPELLANT_GIVEN_NAMES("appellantGivenNames", new TypeReference<String>(){}),
CHANGE_ORGANISATION_REQUEST_FIELD("changeOrganisationRequestField",
    new TypeReference<ChangeOrganisationRequest>(){}),
```

**IA verdict: IMPOSSIBLE via field annotations.** A `HashMap` subclass has no fields for `@CCD` to
attach to. IA must stay on the generate-fresh path. *However*, `AsylumCaseFieldDefinition` is itself a
complete `id → type` catalogue: an alternative "enum-driven" mode could read that enum to derive field
IDs and Java types directly (bypassing the JSON entirely, or cross-checking it), then generate a thin
POJO facade or feed the SDK a synthetic model. That is a different feature from annotation-retrofit and
should be scoped separately.

### 1d. ET (Employment Tribunals) — one in-repo POJO serving two case types (FEASIBLE, best match rate)

Definition: `apps/et/et-ccd-callbacks/ccd-definitions/jurisdictions/england-wales/json/CaseField/`
(directory-per-sheet, fragmented across 15 `CaseField*.json` files) and the sibling
`.../jurisdictions/scotland/json/`.
Model: `apps/et/et-ccd-callbacks/et-shared/src/main/java/uk/gov/hmcts/et/common/model/`.

> **Note on the test-project submodule.** `test-projects/et-ccd-callbacks` is registered in
> `.gitmodules` (branch `json-config-support`) but is **not checked out** in this workspace (the
> directory is empty). All ET evidence below was gathered from the independently-cloned service repo
> at `apps/et/et-ccd-callbacks` (branch `master`, HEAD `f4894cb00`), which contains the same model and
> definitions. Numbers should be re-confirmed against the pinned submodule commit before acting.

**Ownership — the make-or-break, and it is the same shape as SSCS.** ET's model was historically three
separate shared jars (`et-common`, `ecm-common`, `et-data-model` — see `et-shared/README.md`). Those
have been **consolidated into an in-repo Gradle subproject**, `et-shared`
(`settings.gradle:11` → `include 'et-shared'`; `build.gradle:73` → `project(':et-shared')`). So the
model **source lives inside the callbacks repo** — the team can annotate it without a cross-repo change.
**But** `et-shared` is still **published as a jar** (`group 'com.github.hmcts'`, `version` from
`RELEASE_VERSION`) and consumed by sibling ET services — e.g. `apps/et/et-sya-api/build.gradle:233` pulls
`com.github.hmcts:et-shared:1.0.122`. Annotating it is in the team's control, but the annotated classes
flow downstream to every `et-shared` consumer. This is the **same published-but-team-owned situation as
SSCS's `sscs-common`** (§1b): not blocked, but not as self-contained as FPL (whose model is a plain
in-repo POJO with no external jar consumers). The only difference is packaging — ET's source is a
subproject of the service repo, SSCS's is a separate repo — but both are team-owned and consumed only
downstream within the same team.

**One model, two case types.** The `EMPLOYMENT` jurisdiction defines **`ET_EnglandWales`** and
**`ET_Scotland`** (plus `_Multiple`/`_Listings` variants). A single `CaseData`
(`et.common.model.ccd.CaseData`, extends `Et1CaseData` extends `BaseCaseData` —
`818 + 26 + 44 = 888` declared fields across the chain, `CaseData.java` alone is **1926 lines**) serves
**both**: England/Wales declares **777** data-bearing IDs, Scotland **774**, and the two share **760**;
the same model exact-matches **762/777** of EW and **759/774** of Scotland. So retrofit must annotate
**one class against two definitions** and reconcile the ~14 case-type-specific fields — a policy
question (§*Decision needed* item 7) that FPL/SSCS/IA never raised.

**Match-rate measurement (ET_EnglandWales).** `1007` CaseField entries (`1006` distinct IDs); `230`
are `FieldType: Label`, leaving **777 data-bearing IDs**. The model's property union (field names ∪
`@JsonProperty` values, ALL-CAPS constants excluded) is **2289** names across 264 `.java` files.
Exact-name matching:

| Bucket | Count | % of 777 data IDs |
|---|---|---|
| Exact-name match to a model property | **762** | **98 %** |
| Unmatched | 15 | 2 % |

This is by far the highest floor of any archetype — because the ecm/et model **is** the CCD
serialization domain model, hand-built field-for-field against these definitions (every field carries an
explicit `@JsonProperty("<ccdId>")`). Restricting the property union to only the `et.common.model.ccd`
package (the `CaseData` tree and its complex types, excluding report/multiples classes) still yields
**754/777 (97 %)** — the match is not an artefact of unrelated classes. The **15 unmatched** EW IDs
decompose cleanly, and none is a naming divergence needing a fuzzy matcher:

- **9 presentational / CCD-runtime pseudo-fields** with no domain data:
  `componentLauncher`, `LinkedCasesComponentLauncher` (`ComponentLauncher`), `flagLauncher`
  (`FlagLauncher`), `caseHistory` (`CaseHistoryViewer`), `TTL` (`TTL`),
  `et3ClaimantTaskListChecks` (`TaskListCheck`), `dcfYesNo`, plus two `uploadHearingDocumentsWhatAreDocuments*`
  — these are emitted from event/UI config, exactly like FPL's `…Label` stragglers.
- **4 event-only `MultiSelectList`/confirmation fields** (`confirmEt3Submit`, `submitEt1Confirmation`,
  `tseAdminCloseApplicationYes`, `pcqId`) — transient event inputs, not persisted domain state.
- **2 genuine near-misses present in the source under a different owner** (`claimant_Company` exists in
  `CaseData.java`; `subMultipleName` in `bulk/BulkData.java`) — the concept exists; only the top-level
  link is missing.

**Structural finding — the collection wrapper is a REAL problem for ET (unlike FPL).** ET uses a
`*TypeItem` family under `et.common.model.ccd.items` with the id/value shape
(`RespondentSumTypeItem` = `@JsonProperty("id") String id` + `@JsonProperty("value") RespondentSumType value`;
the base `GenericTypeItem<T>` = `String id` + `T value`). Two distinct patterns appear in `CaseData`:

- **13 generic** `List<GenericTypeItem<X>>` fields — the SDK handles these: `ConfigResolver.getComplexType`
  (`ConfigResolver.java:84-96`) and `CaseFieldGenerator.resolveCollectionElementType`
  (`CaseFieldGenerator.java:245-250`) test `elementType.hasGenerics()` and descend to the inner
  generic (`getGeneric(0).getGeneric(0)`), correctly extracting `X`.
- **32 concrete** `List<DocumentTypeItem>`-style fields, where `DocumentTypeItem extends GenericTypeItem<DocumentType>`
  is a **non-generic concrete subclass**. Here `fieldType.getGeneric(0)` resolves to `DocumentTypeItem`,
  which reports `hasGenerics() == false`, so the SDK does **not** descend into `.value` — it would treat
  the whole `{id, value}` wrapper as the element complex type and emit `FieldTypeParameter=DocumentTypeItem`
  instead of `DocumentType`. This is the mirror image of FPL, whose `Element<X>` is always generic and
  always descends correctly. **~32 ET collection fields would mis-resolve** and need either
  `@CCD(typeParameterOverride=…)` per field or an SDK change to unwrap concrete `GenericTypeItem`
  subclasses by walking the superclass generic binding. Medium effort, and the biggest single caveat on
  ET's otherwise-excellent match rate.

**State:** ET's `CaseData` has **no `State` enum and no `state` field** — CCD manages the state
(`State.json`: `Accepted`, `Submitted`, `Rejected`, `Closed`, …) entirely outside the model. So the
FPL-style `State`-enum conflict simply **does not arise**; retrofit generates a fresh `State` enum with
zero risk of clashing with team constants.

**Other annotations:** the model uses **0 `@JsonUnwrapped`** (no prefix-flattening to walk — simpler
than FPL's 30), only **1 field-level `@JsonIgnore`**, but **208 `@JsonIgnoreProperties(ignoreUnknown = true)`**
class annotations (defensive deserialization; harmless to the SDK, which reads fields, not this).

**ET verdict: FEASIBLE — best match rate of any archetype (98 % exact-name floor), in-repo owned
source.** Two real caveats keep it from being push-button: (1) the **32 concrete-`*TypeItem` collections**
mis-resolve under the SDK's `hasGenerics()` descent and need per-field overrides or an SDK tweak;
(2) it is a **published library with downstream consumers**, so annotations propagate beyond the
service. The two-case-types-one-model reconciliation is a policy call, not a technical blocker. Ranks
**above FPL** on match rate and State simplicity, **level with / slightly below FPL** on ownership
cleanliness (FPL's model has no external jar consumers).

### 1e. Civil — huge in-repo POJO, definition in a separate repo (FEASIBLE, strongest pilot candidate)

Definition: `apps/civil/civil-ccd-definition/ccd-definition/civil/CaseField/` (fragment-heavy; sibling
`ccd-definition/generalapplication/` holds the second case type).
Model: `apps/civil/civil-service/src/main/java/uk/gov/hmcts/reform/civil/model/CaseData.java`.

**Two-repo wrinkle.** Unlike FPL (model + definition in one repo) and ET (model + definition in one
repo), Civil splits them: `civil-service` (the service + model) and `civil-ccd-definition` (the
definition) are **separate git repos** (`git rev-parse --show-toplevel` confirms two distinct roots).
A retrofit run reads the definition from `civil-ccd-definition` and must emit its annotation
patch/report against the model in `civil-service` — a cross-repo workflow the CLI must accommodate
(`--input` and `--model-source-root` pointing at different checkouts; the patch applies to a *different*
repo than the definition). This is a workflow wrinkle, not a technical blocker, but it is new
(§*Decision needed* item 8).

**Ownership: cleanest of all — a plain in-repo POJO, no published jar.** The model lives **in the
service's own repo** (`civil-service`) and is *not* published as an artifact, so unlike ET (`et-shared`)
and SSCS (`sscs-common`) — both team-owned but published jars with downstream consumers — there is **no
downstream-consumer coordination at all** for the primary `CaseData`. The team owns and edits it directly.

**The model is genuinely enormous — and split across an inheritance chain.**
`CaseData extends CaseDataParent implements MappableObject`, and the chain runs five deep:

| Class | Lines | ~fields |
|---|---|---|
| `CaseData` | 1679 | 384 |
| `CaseDataParent` | 621 | 298 |
| `CaseDataCaseProgression` | 457 | 300 |
| `CivilCaseData` | 272 | 142 |
| `BaseCaseData` | 16 | 1 |
| **Total** | **3045** | **~1125** |

This is the largest CaseData in CFT by a wide margin (FPL's is 1314 lines / 236 top-level fields; ET's
1926 / 888). The SDK handles the depth for free: `ConfigResolver.resolve` walks via
`ReflectionUtils.doWithFields` (`ConfigResolver.java:66`), which includes **inherited** fields, so the
whole chain is annotatable in place. There is a **companion `GeneralApplicationCaseData`** model
(`civil-service/.../ga/model/GeneralApplicationCaseData.java`) for the second case type — Civil, like
ET, has two case types, but here they map to **two separate model classes** rather than one shared class,
so the ET one-model-two-definitions reconciliation does not apply.

**Lombok / Jackson machinery — verified SDK-safe.** `CaseData` carries `@SuperBuilder(toBuilder = true)`,
`@Jacksonized`, `@Data`, `@EqualsAndHashCode`, and `@Accessors(chain = true)`. `@Accessors(chain=true)`
changes setter return types — irrelevant, because the SDK resolves **fields**, not accessors
(`ReflectionUtils.doWithFields`). `MappableObject` is an inert marker interface (a single `toMap(ObjectMapper)`
default method — no fields, no Jackson impact). `@SuperBuilder`/`@Jacksonized` affect construction, not
reflection. **None of Civil's machinery obstructs SDK field-walking.** The collection wrapper is
`model/common/Element.java` = `UUID id` + `@NotNull @Valid T value` — **generic, exactly like FPL's
`Element<T>`**, so `List<Element<X>>` descends correctly (§1d), no ET-style concrete-subclass problem.

**Match-rate measurement (CIVIL case type).** `1999` CaseField entries; `673` are `Label`, leaving
**1324 data-bearing IDs**. The `civil.model` package property union (ALL-CAPS excluded) is **2984**
names. Exact-name matching:

| Bucket | Count | % of 1324 data IDs |
|---|---|---|
| Exact-name match to a model property | **1248** | **94 %** |
| Unmatched | 76 | 6 % |

The 94 % floor confirms Civil's model, like ET's, was built field-for-field against its definition. The
**76 unmatched** split roughly evenly:

- **~46 primitive / collection / launcher fields.** 16 `Collection`s (`caseWorkerDocuments`,
  `claimantResponseDocuments`, `preTranslationDocuments`, …), assorted `YesOrNo`/`TextArea`/`Text`/`MultiSelectList`
  event inputs, and the usual `flagLauncher`/`*ComponentLauncher`/`caseHistory` presentational pseudo-fields.
  Most are recoverable by a Jackson-faithful matcher or are event-only.
- **~30 custom-complex fields whose type exists only in the definition.** This is the honest hit the
  task asked to sample for: fields like `fastTrackEmployersLiability` (`FastTrackEmployersLiability`),
  `disposalHearingClaimSettling` (`DisposalHearingClaimSettling`), `smallClaimsAllocation`
  (`SmallClaimsAllocation`) reference complex types that **have no Java class in `civil-service`**
  (`find … -name FastTrackEmployersLiability.java` → absent) and whose IDs appear **nowhere in the
  service source**. Civil's SDO (Standard Directions Order) area is *mostly* richly modelled — there are
  ~50 `disposalHearing*`/`fastTrack*` fields in `CivilCaseData.java` — but a residue of composite SDO
  fields live **only in the CCD definition and are manipulated as raw map entries elsewhere**, never
  materialised as typed model fields. These genuinely have no property to annotate and would need a
  synthesised field or generate-fresh supplement (like FPL's role-variant fields). Note: `CaseData` has
  **no `@JsonAnySetter`/`Map<String,Object>` catch-all and no chain-wide `@JsonIgnoreProperties(ignoreUnknown)`**,
  so these definition-only fields are not silently absorbed — they round-trip as untyped JSON handled
  outside the model.

**State: directly reusable — the opposite of FPL.** The `CIVIL` definition declares **18** states in
`State.json`; `enums/CaseState.java` has **36** constants (the extra 18 are GENERALAPPLICATION states).
**All 18 definition state IDs equal `CaseState` constant names exactly** (`CASE_ISSUED`,
`PREPARE_FOR_HEARING_CONDUCT_HEARING`, `JUDICIAL_REFERRAL`, …) and the enum carries **zero
`@JsonProperty`**. Because `StateGenerator` derives the ID from `enumConstant.toString()`, retrofitting
`CaseState` as-is would emit **exactly the right IDs** — no conflict, no fresh-enum workaround. This is a
decisive advantage over FPL, whose enum conflicts on ≥5/9 states.

**Other annotations:** **12 `@JsonUnwrapped`** across the chain (event-data sub-objects like
`respondent1DQ`, `applicant1DQ`, `breathing` — same prefix-flattening the SDK's `appendUnwrapped`
handles) and **152 field-level `@JsonIgnore`** (already correctly excluded by `FieldUtils.isFieldIgnored`).

**Civil verdict: FEASIBLE — strongest pilot candidate.** In-repo model ownership with no downstream-jar
complication (cleaner than ET), a directly-reusable `State` enum (cleaner than FPL), a generic `Element<T>`
wrapper that descends correctly (cleaner than ET's concrete `*TypeItem`), and a 94 % exact-name floor.
Its size (~1125 fields / 3045 lines) makes it the **highest-value** retrofit (largest hand-migration
avoided) and the **best stress test** of the matcher. The two costs are the **two-repo workflow** and the
**~30 definition-only SDO composite fields** with no model field.

---

## 2. The matching algorithm (retrofit mode)

Retrofit hinges on resolving each JSON `CaseField` ID to an existing Java property using the **exact
Jackson rules the SDK itself applies at generation time**, so that a match is guaranteed to
round-trip. The resolver must:

1. **Field name** — default ID is the Java field name (`FieldUtils.getFieldId`).
2. **`@JsonProperty("id")`** — overrides the field name (`FieldUtils.getFieldId` reads it).
3. **`@JsonUnwrapped` prefix, recursively** — descend into unwrapped sub-objects, applying the
   prefix-with-capitalised-concatenation rule (`CaseFieldGenerator.appendUnwrapped`:
   `prefix.concat(capitalize(childName))`). FPL's 30 event-data unwraps must be walked this way.
4. **Getter conventions** — Lombok `@Data`/`@Getter` synthesise getters; the resolver should treat a
   `getFoo()`/`isFoo()` accessor as property `foo` when no field is directly visible.

### Match-classification taxonomy

Every JSON `CaseField` ID lands in exactly one bucket:

| Class | Meaning | Retrofit action |
|---|---|---|
| **EXACT + compatible type** | ID resolves to a property whose Java type infers to the definition's `FieldType` | Add `@CCD(...)` for label/hint/access only |
| **MATCH + type conflict** | Property exists but type mismatches (e.g. def says `Collection`, field is `List<Element<X>>`; or def `Email`, field `String`) | Add `@CCD(typeOverride=…)`; verify `Element`-vs-`ListValue` structural equivalence in round-trip |
| **MATCH on a published-but-owned class** | Property lives in the team's own published jar (SSCS/`sscs-common`, ET/`et-shared`) | Annotate at source; report downstream-consumer coordination (version bump) |
| **UNMATCHED definition field** | No Java property (FPL's 175 role-variants, DFJ courts) | Patch **synthesises a new typed `@CCD` field on the appropriate existing model class** (decision 4) |
| **UNMATCHED Java field** | Model field with no definition ID | Candidate for `@CCD(ignore=true)`; skip if already `@JsonIgnore` |

The **type-conflict** row is where FPL's `Element` wrapper matters: because the SDK matches collections
structurally (§1a), `List<Element<X>>` is *compatible*, not a conflict — a finding that materially
raises FPL's effective match rate above the 28 % name-only floor.

---

## 3. Application mechanism — options and recommendation

The SDK offers **no** way to source annotations from anywhere but the class itself. Confirmed across
the generators: `FieldUtils.getFieldId`, `isFieldIgnored`, `CaseFieldGenerator`, `StateGenerator`,
`FixedListGenerator` all call `field.getAnnotation(...)` / `enumType.getField(...).getAnnotation(...)`
directly. There is **no mixin / side-car registry today**. That constrains the options:

### (a) In-place source rewriting (JavaParser/Spoon AST edit)

Parse the team's model sources, insert `@CCD`/`@ComplexType` annotations and imports, write back.
- **Pros:** most direct; produces exactly what the SDK reads; the annotated model *is* the deliverable.
- **Cons:** needs a `--model-source-root` option and a heavyweight AST dependency (JavaParser); must be
  idempotent/re-runnable (detect existing annotations, merge rather than duplicate); mutates the
  team's source tree.
- **Idempotency:** key each annotation to its field; on re-run, replace the converter-owned annotation
  block (marked with a stable comment sentinel) rather than appending.

### (b) Annotation report + patch file (unified diff)

Emit (1) a human-readable match report (the §2 taxonomy, per field) and (2) a `git apply`-able diff
adding the annotations.
- **Pros:** no new heavyweight deps; safe first step; the team reviews and applies deliberately;
  trivially re-runnable (regenerate the diff).
- **Cons:** the team must apply and re-run to verify; diffs go stale as the model evolves.

### (c) Side-car / mixin registration

Register annotations from an external source (a mixin class or a generated registry) without touching
the model.
- **Status:** **not supported by the SDK today.** Would require a small SDK extension: teach
  `FieldUtils`/`CaseFieldGenerator`/`StateGenerator` to consult a `Map<Class, Map<Field, CCD>>`
  side-car before falling back to `field.getAnnotation`. Now that SSCS is reclassified as team-owned
  (§1b), no archetype in this study *requires* the side-car — all four POJO archetypes own their source
  and can be annotated in place. It remains useful only for a genuinely foreign library (a service
  consuming a model owned by a different team), which none of FPL/ET/Civil/SSCS is. It is an SDK change
  and should **not** be the primary path.

**Recommendation: (b) then (a).** Ship the report + patch first (phase 1–2) — it is safe, dependency-light,
and immediately useful even where full retrofit is impossible (the report alone tells a team their match
rate and their blockers). Graduate to AST in-place rewriting (a) once the matcher is trusted. Keep (c)
in the back pocket only for a truly foreign-library archetype (a model owned by a different team) — which
none of FPL/ET/Civil/SSCS turned out to be — and treat it as a separate SDK proposal if such a case
ever arises.

---

## 4. What the converter still generates in retrofit mode

Retrofit annotates the **model**; it does not eliminate the rest of the pipeline. The converter reuses
the existing **reader → linker** (which already computes, per `CaseField` ID: Java type, `typeOverride`,
label/hint/showCondition, and access classes — see `DefaultDefinitionLinker`/`FieldModel`) and swaps
only the **emit** stage. Still generated:

- **`State` / `UserRole` enums** — either matched to existing enums *or* generated fresh. For FPL,
  the `State` enum **conflicts** (§1a) because `StateGenerator` uses the constant name and ignores
  `@JsonProperty`; retrofit must generate a fresh `State` (constants == state IDs) rather than reuse
  FPL's. `UserRole` similarly must satisfy `HasRole.getRole()` returning the `[BRACKETED]` id.
- **Config classes** (`<CaseType>CoreConfig`, `<CaseType>EventsConfig`) — **always generated**. These
  reference the existing model's getters, so the config emitter must target the *existing* model's
  package and property names (not a generated package) — a new wrinkle vs today's self-consistent output.
- **Access classes** (`HasAccessControl` per grant pattern) — generated as today.
- **Callbacks** — stubbed/forwarded as today (`--callback-mode`).

### CLI shape

Extend `ConvertCommand` (which today takes `--input`, `--output-src`, `--model-package`,
`--config-package`, …):

```
convert --retrofit \
        --input <definition-dir> \
        --model-source-root <path/to/service/src/main/java> \
        --model-package uk.gov.hmcts.reform.fpl.model \
        --config-package uk.gov.hmcts.reform.fpl.config \
        [--report-only]   # emit match report + patch, don't rewrite
```

`--model-source-root` + `--model-package` tell the matcher where the existing model lives;
`--report-only` selects mechanism (b) over (a). Note `ConfigResolver` hardcodes basePackage
`uk.gov.hmcts`, so all three archetypes' packages qualify with no change.

---

## 5. Verification story

The correctness proof must be the **same round-trip harness** that guards the generate-fresh path
(`RoundTripTest` / `NormalisingCcdConfigComparator`, documented in
[`json-conversion-fidelity.md`](json-conversion-fidelity.md)). Today it: converts JSON → Java →
compiles → runs the SDK generator → semantically diffs the regenerated definition against the input.

For retrofit, redirect the "compile + run SDK generator" step at the **team's annotated sources**
instead of the converter's generated `srcOut`:

1. Point `GeneratedSourceCompiler` at `--model-source-root` (post-annotation) plus the generated
   config/enum/access classes.
2. Run `GeneratorRunner` over the resulting classloader scanning the existing model package.
3. Diff with `NormalisingCcdConfigComparator` exactly as today. **The enumerated normalisation rules
   (`CASE_HISTORY`, `FIELD_TYPE_COMPLEX`, `LIVE_FROM`, …) apply unchanged** — a retrofitted model must
   clear the same bar as a generated one, no weaker.

**Classpath complication (measured on FPL):** the fresh generate-and-compile path compiles a
self-contained model. FPL's *existing* model does not — `CaseData.java` alone imports ~40 FPL-internal
packages (`fpl.enums.*`, `fpl.model.common.*`, `fpl.model.event.*`, `ccd.model.OrganisationPolicy`,
jakarta validation, Lombok) and the model spans 366 files. To compile FPL's annotated model for
round-trip, the harness needs FPL's full compile classpath (Lombok, jakarta.validation, the
`uk.gov.hmcts.reform.ccd.model` common types, Apache Commons). This is much heavier than the current
minimal-model round-trip and likely means running the round-trip *inside the service's own Gradle
build* (as a plugin task) rather than in the converter's test module. SSCS is comparable — its 373-file
`sscs-common` model drags in Guava, commons-lang3/collections4, Spring, Jackson and jsr310
(`sscs-common/build.gradle:171-186`), so the round-trip is best run inside `sscs-common`'s own Gradle
build (where that classpath already exists) rather than the converter test module. This is the single
biggest engineering cost of retrofit verification.

---

## 6. Feasibility verdict per archetype

| Archetype | Shape | Match evidence | Verdict |
|---|---|---|---|
| **Civil** | POJO, own repo, **definition in separate repo**; ~1125 fields / 3045 lines across 5-class chain | 1248/1324 (**94 %**) exact-name floor; `Element<T>`≡`ListValue` (generic, descends); **`State` enum directly reusable — 18/18 IDs match, no `@JsonProperty`**; `@JsonUnwrapped`×12 & `@JsonIgnore`×152 fit; ~30 SDO composite fields exist only in the definition | **FEASIBLE — strongest pilot candidate** |
| **ET** | POJO in in-repo `et-shared` module, **published as a jar** to sibling services; one `CaseData` (888 fields) serves **two case types** | 762/777 (**98 %**) exact-name floor; **no `State` enum/field** (no conflict); `@JsonUnwrapped`×0, `@JsonIgnore`×1; **32 concrete `*TypeItem` collections mis-resolve** (SDK `hasGenerics()==false`, needs overrides/SDK fix) | **FEASIBLE — best match rate**; caveats: published-jar consumers + concrete wrapper |
| **FPL** | POJO, own repo, no SDK yet | 408/1448 (28 %) exact-name floor; `Element`≡`ListValue` structurally; `@JsonUnwrapped`×30 & `@JsonIgnore`×69 already fit; **`State` enum conflicts (≥5/9 IDs)**; 175 role-variant + 44 DFJ fields have no Java field | **FEASIBLE — assisted migration** |
| **SSCS** | POJO in the SSCS team's own **published** `sscs-common` jar (consumed only by SSCS repos); 256 top-level fields / 517 reachable props | 512/547 (**94 %**) exact-name floor; **`State` enum directly reusable — 21/21 IDs match via `@JsonValue toString()`**; `@JsonUnwrapped`×23 (most of any archetype, all prefix-less) & `@JsonIgnore`×47 fit; **~120 concrete `List<Wrapper>` collections mis-resolve** (worst `hasGenerics()==false` case); 14 def-only complex + 21 event-only unmatched; needs small `StateGenerator` `toString()`/`name()` fix | **FEASIBLE**; caveats: published-jar consumers (team-owned) + worst collection wrapper |
| **IA** | `HashMap` subclass + 869-const enum | zero annotatable fields | **IMPOSSIBLE via annotations**; enum-driven generate-fresh is the only path |

**Pilot ranking:** **Civil > ET > SSCS > FPL**. Civil wins on the cleanest ownership (in-repo, no
published jar), a directly-reusable `State` enum, a generic `Element<T>` wrapper that descends correctly,
and the highest migration value (largest model). ET has the best raw match rate (98 %) but is a
team-owned *published* library and its 32 concrete `*TypeItem` collections need SDK work. SSCS matches
Civil's floor (94 %) with a directly-reusable `State` enum and is likewise a team-owned published jar,
but its collection wrapper is the most hostile of any archetype (~120 concrete `List<Wrapper>` fields
mis-resolve, worse than ET's 32) — so it ranks just below ET on collection cost while sitting well above
FPL on match rate and `State` reusability. FPL is the cleanest greenfield story (plain in-repo POJO, no
published jar) but has the lowest floor (28 %) and a conflicting `State` enum.

### Risks, ranked

1. **Model compile classpath for round-trip** (§5) — heaviest cost; likely forces the round-trip into
   the service's own build. High effort, high certainty.
2. **Concrete collection-wrapper resolution (SSCS worst, then ET)** — a wrapper whose element is a
   *non-generic* concrete class (`value`-bearing) is not unwrapped by the SDK's `hasGenerics()` descent
   (`ConfigResolver.java:84-96`, `CaseFieldGenerator.java:250-262`: `if (elementType.hasGenerics())
   elementType = elementType.getGeneric(0)`), so it emits the wrong `FieldTypeParameter`. **SSCS is the
   worst case: ~120 `List<Concrete>` field-instances (58 distinct wrappers like `Hearing{value}`,
   `SscsDocument extends AbstractDocument<value>`, `ElementDisputed{value}`)** mis-resolve; **ET has 32**
   (`List<DocumentTypeItem>`, subclasses of `GenericTypeItem<T>`). Needs per-field `typeParameterOverride`
   or an SDK change to unwrap a concrete `value`-bearing wrapper via its field/superclass generic binding.
   Medium-to-high effort; **does not affect FPL/Civil** (both use a generic `Element<T>` that descends
   correctly).
3. **`State`/`UserRole` enum ID divergence** — `StateGenerator` derives the ID from
   `enumConstant.toString()` (`StateGenerator.java:26`), so reusability turns on what `toString()`
   returns. FPL's enum returns the raw constant name and diverges (≥5/9), so it cannot reuse its enum and
   must generate fresh. SSCS *can* reuse its enum: it overrides `toString()` via `@JsonValue` to return
   the lowercase id (`State.java:41-45`), so all 21 IDs match verbatim — Civil likewise (constants ==
   IDs). **But `StateGenerator.enumToJsonMap` (`StateGenerator.java:42`) also does
   `enumType.getField(enumConstant.toString())`, which throws `NoSuchFieldException` when `toString()` is
   overridden** (looks up field `appealCreated`, not `APPEAL_CREATED`); retrofit must switch that to
   `((Enum<?>) enumConstant).name()`. Document loudly and fix the lookup.
4. **Synthesised / definition-only fields** (FPL's 175 `…CTSC/LA/Removed`; Civil's ~30 SDO composites
   like `fastTrackEmployersLiability` that have no Java class and are handled as raw map entries) — no
   1:1 Java field. Convention decided: the patch synthesises them as new typed `@CCD` fields on the
   existing model class (decision 4). Residual risk is placement (which class in a multi-class chain
   like Civil's) and naming collisions with existing members. Medium effort.
5. **Published-jar propagation (SSCS and ET)** — neither is *blocked*: both models are team-owned. But
   both are **published jars** consumed downstream within the same team — SSCS's `sscs-common`
   (`com.github.hmcts:sscs-common`, consumed by `sscs-tribunals-case-api`/`sscs-case-loader`/
   `sscs-ccd-case-migration`) and ET's `et-shared` (consumed by sibling ET services). Annotations flow to
   every consumer via a version bump, so migration is coordinated across the team's own repos — a
   coordination cost, not a hard block. **Correction:** SSCS was previously classified BLOCKED as an
   "unowned" library; it is in fact team-owned (`apps/sscs/sscs-common`,
   `sscs-common/build.gradle:13,122`), so it is feasible like ET. Only a model owned by a *different*
   team would be a true block, and none of the studied archetypes is.
6. **Matcher precision** — a wrong match silently corrupts the definition; the round-trip is the
   backstop, so matcher and round-trip must ship together.

### Phased implementation plan

- **Phase 1 — report-only matcher. IMPLEMENTED (2026-07-14).** A `--retrofit --report-only` mode on
  `ConvertCommand` parses the team's model source with JavaParser (a converter-only dependency; kept
  out of the SDK), resolves every data-bearing `CaseField` ID with the Jackson-faithful resolver (§2 —
  `RetrofitMatcher`/`PropertyResolver`/`TypeInference`/`StateEnumAnalyser`), classifies into the
  taxonomy, and writes `retrofit-report.md` + `retrofit-report.json`. No source mutation, no patch. See
  the README's *Retrofit mode* section. Validated on FPL, ET, Civil and SSCS (`RetrofitFixtureMatchTest`,
  submodule-guarded, tagged `round-trip`) plus a golden fake-model unit test in the fast `check`
  (`RetrofitMatcherGoldenTest`), and confirms an IA-style `HashMap` model reports "retrofit not
  applicable".

  **Measured floors from this resolver (they supersede the hand floors below).** The resolver reports
  a *resolved rate* (name resolution = EXACT + TYPE_CONFLICT) and, separately, the *exact* rate:

  | Fixture | Resolved (name) | Exact | Hand floor (§1, name-only) | Note |
  |---|---|---|---|---|
  | FPL (`CARE_SUPERVISION_EPO`) | **71 %** | 36 % | 28 % | Far above the hand floor: the hand count matched only top-level names, whereas the resolver walks FPL's 30 prefix-less `@JsonUnwrapped` event-data objects and its `CaseDataParent` superclass (§1a explicitly lists these as recoveries the real matcher would make). |
  | ET (`ET_EnglandWales`) | **97.9 %** | 46 % | 98 % | Reproduces the hand floor — ET's model is already flat/field-per-CaseField. 37 concrete `*TypeItem` wrappers surfaced. |
  | Civil (`CIVIL`) | **94.6 %** | 64 % | 94 % | Reproduces the hand floor. `Element<T>` is generic and descends correctly (few concrete wrappers). |
  | SSCS (`Benefit`) | **93.6 %** | 27 % | 94 % | Reproduces the hand floor. 44 concrete `List<Wrapper>` fields surfaced (the most hostile). |

  The exact vs resolved split is a phase-1 addition the hand floors did not distinguish: a high
  *resolved* rate with a lower *exact* rate is the normal, expected shape (most conflicts are the
  benign `@CCD(typeOverride)`/`typeParameterOverride` cases, not naming misses). One finding updates
  §1a: because the resolver derives state IDs the way the post-decision-3 SDK does (honouring
  `@JsonProperty` on constants, `StateId`), FPL's `State` enum reconciles with **zero** conflicts —
  the pre-decision-3 "≥5/9 conflict" no longer holds, and FPL's enum is now reusable like the others.
- **Phase 2 — patch emission for the POJO archetype. IMPLEMENTED (2026-07-14).** `--retrofit` without
  `--report-only` emits a `git apply`-able annotation patch (`retrofit.patch`) adding `@CCD`
  annotations to the team's model — matched fields (label/hint/showCondition/regex/categoryID/
  searchable/retainHiddenValue/min/max/access, mirroring `FieldEmitHelper`), `typeOverride`/
  `typeParameterOverride` for type conflicts (concrete value-wrapper collections get
  `typeParameterOverride` per decision 8), `@CCD(ignore = true)` for unmatched Java fields, synthesised
  typed `@CCD` fields for unmatched definition fields (decision 4, in a delimited block on
  `--model-class`), and the same treatment for complex-type members — plus the still-generated
  companion config/enum/access sources retargeted at the team's model (reusing generate mode's
  `CoreConfigEmitter`/`EventsConfigEmitter`/`AccessClassEmitter`/`EnumEmitter` via an `EmitContext`
  that points them at the team's `CaseData`/reused `State`; `FieldClusterer` is skipped so the model's
  own `@JsonUnwrapped` structure is honoured rather than synthesising clusters). The patch is rendered
  with JavaParser's `LexicalPreservingPrinter` + java-diff-utils and is idempotent (skips fields
  already carrying `@CCD`). Reuses the team's `State` enum when every state ID resolves (decision 3),
  generating a fresh one otherwise. The gate is `RetrofitRoundTripTest` (tagged `round-trip`): it
  applies the emitted patch to the fake model in-memory, compiles the patched model + companion
  sources, runs the SDK generator, and asserts `NormalisingCcdConfigComparator` finds **zero
  unexplained diffs** — covering an exact match with label+access, an `Email` type conflict, a
  concrete-wrapper collection (`typeParameterOverride`), an unmatched Java field (ignore), a
  synthesised definition-only field placed on an event, a complex type with member labels, a reusable
  `@JsonProperty` `State` enum, and a fixed list matched to an existing model enum. A fast
  `RetrofitPatchEmitterGoldenTest` pins the patch content in `check`. **Not yet piloted against the
  real Civil/ET/SSCS checkouts** — that is the phase-3 build-integration step below.
- **Phase 3 — end-to-end verification pipeline + Civil pilot. IMPLEMENTED (2026-07-14).** A
  `bin/retrofit-verify` bash pipeline runs the retrofit round-trip inside the *service's own* Gradle
  build (decisions 5 & 9), and a `RetrofitVerifyCli` diffs the generated output against the definition
  repo reusing the converter's reader + `ExpectedDefinitionBuilder` overlay/env handling and
  `NormalisingCcdConfigComparator`. See the *Phase 3 status* section below for the pilot outcome.
  In-place AST rewriting (mechanism (a)) remains a later nicety.

---

## Phase 3 status — pipeline + Civil pilot (2026-07-14)

**Deliverables shipped.**

- `sdk/ccd-definition-converter/bin/retrofit-verify` — orchestrates convert → copy → apply → publish
  + generate → diff, with `--stop-after`, `--skip-publish`, `--env`, `--overlay-suffix`,
  `--gradle-arg` flags. **Never mutates** the model/definition checkouts (copies the model repo,
  patches only the copy; verified the fixture submodules show no modifications after a full run).
- `RetrofitVerifyCli` (converter test sources) + a `:ccd-definition-converter:retrofitVerify` Gradle
  task — aggregates the definition repo via the converter's `JsonDefinitionReader` and the round-trip
  harness's `ExpectedDefinitionBuilder` (factored for reuse, not duplicated), runs
  `NormalisingCcdConfigComparator`, prints residuals bucketed by sheet.
- README *Retrofit mode phase 3* section documenting usage.

**Pilot: Civil** (`CIVIL`, model `uk.gov.hmcts.reform.civil.model.CaseData`, definition
`civil-ccd-definition/ccd-definition/civil` — the exact inputs `RoundTripTest.civilRoundTrips` uses).

| Stage | Outcome |
|---|---|
| 1. convert (retrofit patch + companion) | **PASS** — 94.6 % resolved / 63.8 % exact; 251 model files patched; companion config/enum/access + definition-only complex-type classes emitted |
| 2. copy model repo | **PASS** — model repo copied to the gradle build dir; submodule untouched |
| 3. `git apply` the patch | **PASS** — all 251 file diffs apply cleanly |
| 4. publish SDK + `generateCCDConfig` in the copy | **PARTIAL** — dependency resolution + companion wiring succeed; compile reaches **20 errors in 2 companion complex-type classes** (down from an initial 100+), from **2 genuinely ambiguous type references** (see below) |
| 5. diff generated vs definition | **not reached** — blocked by stage 4 |

The pilot did exactly what a pilot is for: it drove out real converter/patch bugs that the synthetic
`RetrofitRoundTripTest` fixture could never exercise. **Six converter bugs found and fixed:**

1. **`@CCD` string literals not fully escaped** (`CcdAnnotationRenderer.quote`) — a label containing a
   newline/tab produced an invalid Java string literal that JavaParser rejected. Now escapes
   `\n\r\t\f\b` (plus `\`/`"`), matching JavaPoet's `$S`. *(Hit immediately on Civil's multi-line
   labels.)*
2. **Patch emitter parsed at the default JavaParser language level** (`RetrofitPatchEmitter`) — Civil's
   `model/Result.java` is a `sealed` interface, which the default level rejects. Now parses at
   `JAVA_21` with lexical preservation on.
3. **Unified-diff line model off-by-one at EOF** (`RetrofitPatchEmitter.unifiedDiffFor`) — `split("\n",
   -1)` fabricated a phantom trailing empty line for newline-terminated files, so any hunk reaching
   end-of-file (every synthesised-fields block, inserted before the class's closing brace) had wrong
   line numbers and `git apply` rejected it. The in-memory `RetrofitRoundTripTest` never caught this
   because it applies via java-diff-utils' own `applyTo`, which shares the phantom convention. Added
   `splitGitLines` (drops the phantom) + a `\ No newline at end of file` marker for the 18 Civil model
   files that lack a trailing newline.
4. **Complex-type / model-class resolution ignored the model package and matched nested types**
   (`ModelSourceIndex`, `RetrofitPatchEmitter.planComplexTypeMembers`) — `bySimpleName(id, null)`
   resolved the complex type `Hearing` to the `Hearing` interface **nested inside the sealed
   `CaseDataPredicate`**, synthesising uninitialised fields into an interface body. Added
   `complexTypeClass` (top-level classes only, model-package-preferred) and a top-level `locateRoot`.
5. **PascalCase definition IDs synthesised colliding fields** (`RetrofitMatcher`) — 17 CIVIL CaseField
   IDs are PascalCase (`SmallClaimHearingInterpreterRequired`) while the model field is camelCase; the
   exact-name miss classified them UNMATCHED and synthesised a field whose decapitalised name collided
   with the existing one. Added first-letter-case aliasing so the existing field is matched and bound
   via `@JsonProperty` instead.
6. **Definition-only complex types not generated, and their references not imported** (retrofit dropped
   `ComplexTypeEmitter` entirely). Added `RetrofitComplexTypeEmitter` (emits `@ComplexType` classes only
   for the ~26 definition-only complex types with no model class — Civil's SDO composites
   `FastTrackEmployersLiability`, `SmallClaimsAllocation`, … the proposal flagged in §1e), synthesised-
   field type imports, and `retrofitTypeFqnOverrides` so a companion complex type in `modelPackage`
   imports member types that live in the model's other sub-packages (`JudgmentAddress` in
   `model.judgmentonline`, enums in `.enums`, …) at their real FQN instead of defaulting wrongly to
   `modelPackage`.

These fixes cut the stage-4 compile from 100+ errors to **20**, all in two companion complex-type
classes (`HearingLRspec`, `RequestedCourtLocationLRspec`).

**Residual blocker (honest).** The 20 errors come from **2 ambiguous simple type names** the definition
references but that exist in *more than one* model sub-package, with nothing in the `ComplexTypes` JSON
to disambiguate:

- `HearingLength` — an `enum` in `uk...civil.enums.dq` **and** a `class` in
  `uk...civil.ga.model.genapplication`;
- `CaseLocationCivil` — a `class` in `uk...civil.model.genapplication` **and** in
  `uk...civil.model.defaultjudgment`.

`topLevelFqnsOutside` deliberately **omits** ambiguous names rather than guess (a wrong pick would
silently corrupt the generated definition), so these two default to `modelPackage` and fail to resolve.
This is the "assisted-migration, not push-button" reality the proposal predicted (§1e's two-repo
wrinkle + definition-only composites) and matches its up-front expectation that the pilot "will NOT be
zero-diff first time and may not even reach the diff step." Resolving it needs a per-reference package
hint (operator-supplied disambiguation, or richer `ComplexTypes` metadata) — a bounded follow-up, not a
converter redesign.

**No external (Azure Artifacts) blocker was hit.** civil-service already declares `mavenLocal()` first,
the SDK published to mavenLocal cleanly, and `generateCCDConfig` resolved civil-service's dependencies
without auth failures — dependency resolution was never the wall; the annotated-model compile was.

**Gates stay green** after all six fixes: `:ccd-definition-converter:check`,
`:ccd-config-generator:test`, `:ccd-definition-converter:roundTripTest` (incl. `RetrofitRoundTripTest`
zero-diff), and `:ccd-gradle-plugin:test` all pass; checkstyle Google/-Werror clean; the fixture
submodules show no modifications.

---

## Decisions (maintainer, 2026-07-14)

All ten questions are now decided (or delegated as implementation detail):

1. **Scope — DECIDED.** Two modes only. **Retrofit mode** (apply annotations to the team's existing
   models) is the path for every POJO service — FPL, Civil, ET, SSCS. **Generate mode** (the converter's
   existing JSON→fresh-model output) remains for map-based services — IA. Side-car annotation support is
   dropped (no studied archetype needs it now SSCS is confirmed team-owned).
2. **Mechanism — delegated (implementation detail).** Going with the recommendation: patch-file first
   (report + `git apply` diff), in-place AST rewriting later once the matcher is trusted.
3. **`State`/`UserRole` policy — DECIDED: update `StateGenerator`.** The SDK will honour `@JsonProperty`
   on state enum constants when deriving the CCD state ID — a documented, justified SDK change (it aligns
   states with `FieldUtils.getFieldId`'s Jackson-faithful resolution, and the old behaviour generated IDs
   that could never match the runtime state values Jackson actually serialises). Includes the
   `StateGenerator` reflection fix (`Enum.name()` not `toString()` for the field lookup) so
   `@JsonValue`-overriding enums like SSCS's don't throw. Unannotated enums are unaffected. This makes
   FPL's enum reusable too; fresh-enum generation remains the fallback for teams with no state enum (ET).
4. **Definition-only fields — DECIDED: synthesise them into the team's existing model.** The annotation
   patch adds each unmatched definition field as a new typed `@CCD` field on the appropriate existing
   class (FPL's 175 `…CTSC/LA/Removed` variants; Civil's ~30 SDO composites like
   `fastTrackEmployersLiability`, currently raw map entries — the typed field is an upgrade). No
   generated supplement class, no second model.
5. **Round-trip host — DECIDED: the service's own build**, driven by the verification pipeline (see 9).
6. **IA / map-based — DECIDED: generate mode *is* the IA path.** No bespoke enum-driven mode reading
   `AsylumCaseFieldDefinition`; map-based services use the converter's existing JSON→fresh-model output.
7. **ET two case types, one model — DIRECTION: subclassing. VALIDATED (2026-07-14).** Annotate the shared
   `et-shared` `CaseData` as a base class carrying the ~760 common fields; add one thin subclass per case
   type (`ET_EnglandWales`, `ET_Scotland`) carrying that case type's specific fields, each bound by its
   own `CCDConfig`. Viable because the SDK's field reflection (`ReflectionUtils.doWithFields`) walks
   superclasses. **Spike outcome (`EtSubclassSpikeTest`, tagged `round-trip`):** a fake base
   `EtCaseData` (shared `claimantName`/`claimType`/`respondent`) plus two thin subclasses each adding one
   field, bound by two `CCDConfig`s, was compiled and run through the generator. Confirmed
   end-to-end: **each case type's generated `CaseField` set equals base ∪ its own subclass field**
   (`ET_EnglandWales` gets `englandWalesOffice` and not `scotlandOffice`; `ET_Scotland` the reverse), and
   the **shared complex type (`Party`) and shared enum-backed `FixedList` (`EtClaimType`) resolve
   identically through both subclasses** (same `ComplexTypes`/`FixedLists` rows). So the SDK's
   complex-type and enum resolution behaves identically through the subclass, as hoped. **Caveats
   found:** (a) each subclass needs `@EqualsAndHashCode(callSuper = true)` (a Lombok warning otherwise,
   harmless to generation); (b) the generator context needs an `ObjectMapper` on the classpath (the
   SDK's runtime `CallbackController`/`CcdCallbackExecutor` require one) — in a real service this
   comes from Spring Boot's own Jackson autoconfiguration on the service's existing
   `@SpringBootApplication` (the converter does not emit one into the service tree; see *Wiring* under
   the README's retrofit phase 2), so no action for teams. The spike's own harness stands in a minimal
   `@SpringBootApplication` purely because it has no such service context to lean on. Not exercised
   in the spike:
   per-case-type *divergence of a shared field's type/label* (both case types share identical base-field
   metadata here); that remains a per-field policy call when a real ET migration needs it, but is not a
   structural blocker.
8. **Concrete collection wrappers — DECIDED: accept the per-field `@CCD(typeParameterOverride=…)`
   noise.** No SDK generics change. The patch emitter writes the override on every mis-resolving wrapper
   field (ET ~32, SSCS ~120); the match report calls them out so teams can later rationalise wrappers if
   they choose.
9. **Two-repo workflow (Civil; also ET/SSCS jar consumers) — DECIDED: clone-both + mavenLocal for now.**
   The verification pipeline clones the model repo and the definition repo, applies the annotation patch,
   publishes the annotated model (and the SDK where needed) to `mavenLocal`, links the service build
   against it, and runs the round-trip inside the service's own build. Properly paired cross-repo PRs
   come later.
10. **Published-library propagation (ET, SSCS) — accepted** (subsumed by decision 1: all POJO services
    retrofit). Annotations flow to same-team consumers via a normal version bump; sequence the jar
    release before consumer migration. A coordination cost, not a block.
