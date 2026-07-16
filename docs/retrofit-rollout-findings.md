# Retrofit Rollout — Findings (2026-07-14)

The retrofit pipeline (phases 1–3, see [retrofit-existing-models-proposal.md](retrofit-existing-models-proposal.md))
was applied to **every case type except IA** (which uses generate mode by decision 6), in parallel —
one lane per case type, plus the Civil pilot which ran the full two-repo `bin/retrofit-verify`
pipeline. Detailed per-lane reports and the emitted patches are preserved under
[`sdk/ccd-definition-converter/retrofit-reports/`](../sdk/ccd-definition-converter/retrofit-reports/).

## Scoreboard

| Case type | Resolved | Exact | Patch apply | Validation achieved | Converter bugs | Verdict |
|---|---:|---:|---|---|---:|---|
| **CIVIL** (civil-service) | 94.7% | 63.8% | clean (250 files) | **round 2: full pipeline to DIFF** — convert→copy→patch→publish→generate all pass; 9,098 residual diff lines bucketed | 6 (r1) + 5 (r2) **all fixed** | Feasible — first end-to-end round-trip; residuals are enumerable follow-on work |
| **ET_EnglandWales** (et-shared) | 97.9% | 45.8% | clean | patched model + all companions compile clean (structural/type level) | 1 (import) | Feasible — best match rate |
| **ET_Scotland** (et-shared) | 97.9% | 45.4% | clean | as above | same bug | Feasible — subclass shape validated (below) |
| **Benefit** (sscs-common) | 93.6% | 26.9% | clean (94 files) | **real javac, baseline-proven classpath: 0 errors** (round 3; was 38→31) | 3 + A2/B2-borderline/Bug4 **all fixed** | Feasible — patched model + companions compile clean |
| **GrantOfRepresentation** (probate-back-office) | 88.1% | 40.0% | clean (22 files) | patch proven **compile-neutral** vs baseline; 132 companion classes → bytecode | 1 | Feasible — first measurement; friendliest wrapper story |
| **PRLAPPS** (prl-cos-api) | 73.3% | 56.9% | clean (233 files) | real javac of full 2,218-file tree: ~18 converter-attributable errors | 3 | Feasible — first measurement |
| **CARE_SUPERVISION_EPO** (fpl) | 73.4% | 37.0% | clean (122 files, +3,387/−357) | phases 1–2 complete; compile validation incomplete (lane agent killed by a session restart mid-javac) | ~2 clusters (from surviving logs) | Feasible — resolver lifted the 28% hand floor to 73.4%; needs re-validation after fix round |
| **Asylum** (ia) | — | — | — | — | — | Generate mode (map-based model, decision 6) |

> The "Converter bugs" column is the per-lane snapshot at rollout time. All of them were worked in the
> central fix round (2026-07-15) — see the [consolidated bug list](#consolidated-converter-bug-list-for-the-central-fix-round)
> below for per-bug fix/not-a-bug status. The patches above are **pre-fix**; re-run each lane with the
> rebuilt CLI before relying on them.

Reading the numbers: *Resolved* = definition CaseField IDs the Jackson-faithful resolver bound to an
existing model property (exact + type-conflict); type-conflicts are not gaps — they receive
`@CCD(typeOverride/typeParameterOverride)` in the patch. The unresolved remainder is synthesised onto
the model per decision 4.

## Headline findings

1. **Retrofit is viable everywhere it was attempted.** Both previously-unmeasured case types landed
   in the feasible band (probate 88.1%, prl 73.3%), prl with a perfectly reusable 15/15 State enum and
   zero concrete wrappers. No POJO case type is blocked.
2. **Every patch applied cleanly** — zero rejected hunks across all six patch sets (after the pilot's
   `git apply` EOF fixes). The core matching/annotation logic held up on every real model:
   every lane's 10/10 random spot-checks were correct.
3. **The ET two-case-types question (decision 7) is settled with data.** The two per-case-type
   annotation sets have identical ID coverage (816 each); semantically: **762 fields identical →
   shared `EtCaseData` base**, **31 case-type-specific → subclasses** (17 E&W / 14 Scotland), **12
   genuine divergences** needing a policy call (9 label-only, 3 structural: one `categoryID`, two
   FixedList references). Skeleton base+subclass files are drafted in
   `retrofit-reports/et-subclass-skeletons/`. A bounded 43-field reconciliation, not a blocker.
4. **The Civil pilot proved the two-repo pipeline end-to-end mechanics** (decision 9): convert →
   copy → apply → mavenLocal publish → init-script wiring (service `build.gradle` untouched) →
   `generateCCDConfig`. No Azure Artifacts friction. The wall is converter correctness on a huge real
   model — exactly what the pilot was for; it drove the compile from 100+ errors to 20 and fixed six
   converter bugs a synthetic fixture could never surface.
5. **State-enum policy (decision 3) worked as designed in all four directions**: reuse via constant
   names (Civil), reuse via `@JsonValue toString()` (SSCS), reuse via `@JsonProperty` (FPL, prl),
   generate-fresh on genuine conflict (probate, all 38 states) and on absence (ET).

## Consolidated converter bug list (for the central fix round)

Grouped by family; "pilot-fixed" = already fixed by the Civil pilot *after* the parallel lanes'
CLI snapshot was built, so those lanes may have exercised the pre-fix behaviour — verify at fix time.

**Fix-round status (2026-07-15, central fix round).** Every item below now carries a status and the
fix location + regression test. Gates green: `-p sdk :ccd-definition-converter:check
:ccd-config-generator:test :ccd-definition-converter:roundTripTest :ccd-gradle-plugin:test`
(checkstyle Google/-Werror, generate mode byte-identical, SDK `src/main` untouched). CLI rebuilt. The
lanes must be **re-run** with the rebuilt CLI — the emitted patches change materially for A2, B1, B2,
B3, C1 and the patch root (now repo-rooted for every lane).

**A. Dropped type overrides (fidelity bugs — highest priority)**
- A1 (probate) — **NOT A BUG (report-honesty fixed).** `AddressUK`, `Number`, `DateTime`,
  `DamageCulprit`, `OriginalDocuments`, `MoneyGBP` are **not** `FieldType` enum constants (only
  `Document`/`Date`/`Collection`/`YesOrNo`/… are — SDK `src/main`, untouchable), so
  `@CCD(typeOverride = FieldType.AddressUK)` cannot compile; the phase-2 emitter's `isFieldTypeConstant`
  guard is **correct** to drop them (that is the `Document`-emits-6/6 asymmetry). These are genuine
  model-vs-definition type divergences a maintainer must reconcile on the model by hand. The real
  defect was that **phase-1's `action` text recommended the uncompilable annotation**; now it says
  "genuine type divergence: … reconcile the model type by hand" (`RetrofitMatcher.conflictAction`,
  `TypeReconciler.isFieldTypeConstant`). Regression: `RetrofitMatcherGoldenTest` (dateOfBirth →
  DateTime). (`OriginalDocuments` was also mis-read as a conflict — the model field IS typed
  `OriginalDocuments`, so it is an exact match.)
- A2 (sscs) — **FIXED (round 3, 2026-07-15). The round-1 "verified on the real sscs model" claim was
  WRONG when written** — the reconciler fix was real but never reached sscs's `ReasonableAdjustmentsLetters`,
  because `ModelSourceIndex.complexTypeClass` resolved complex-type IDs **case-sensitively**: sscs's ID is
  camelCase (`reasonableAdjustmentsLetters`) while the class is PascalCase (`ReasonableAdjustmentsLetters`), so
  the lookup missed and the complex type fell through to a spuriously-generated lowercase companion — its members
  silently un-reconciled, the class absent from the patch, no gap. The round-1 regression could not have caught
  it: the golden definition's ID `Party` is already PascalCase. **Round-3 fix:** `complexTypeClass` (and
  `fqnForSimpleName`) now fall back to a case-insensitive class match (enums excluded, single-match only), and
  `RetrofitConverter` merges `ModelSourceIndex.caseInsensitiveClassAliases()` into the emit `fqnOverrides` so
  companion member/synthesised-field types typed by a camelCase ID (`panel`, `name`, `contact`) bind to the real
  PascalCase class instead of a dangling companion. On the real sscs model `ReasonableAdjustmentsLetters` (+ ~57
  more camelCase-ID complex types) now patch onto their classes; all 5 `List<Correspondence>` members get
  `typeOverride=Collection, typeParameterOverride="correspondence"` (proven by the regenerated patch + a 0-error
  javac compile). Also widened the B3 guard to any `@Builder`-bound explicit constructor (sscs's `Appeal`, which
  A2 newly reached). Regressions:
  `ModelSourceIndexTest.resolvesCamelCaseComplexTypeIdToItsPascalCaseClass` +
  `RetrofitPatchEmitterGoldenTest.writesTypeParameterOverrideOnNestedComplexTypeCollectionMember`.

**B. Synthesised-field placement (design issues, not crashes)**
- B1 (prl) — **FIXED.** `RetrofitPatchEmitter.dropExistingFieldCollisions` skips synthesising a field
  whose Java name already names a declared member (a `@JsonUnwrapped` parent like
  `CaseData.allegationOfHarm`, or a `@JsonProperty`-renamed field like `Court.courtName`) — verified on
  the real prl model (Court/CaseData/Child collisions now skipped with a gap; the surviving `courtName`
  synthesises are on unrelated classes that lack the field). Generalises the pilot's PascalCase-collision
  fix from resolved to *declared* members. Regression:
  `RetrofitPatchEmitterGoldenTest.skipsSynthesisingAFieldThatCollidesWithAnExistingModelMember`.
- B2 (sscs) — **FIXED; borderline "+1 tips it" now genuinely resolved (round 3, 2026-07-15).**
  `SynthesisPlacement` detects when synthesising onto a Lombok all-args-constructor class would exceed a safe
  ~250-slot threshold (long/double = 2 slots) and moves the fields off the root. The common overflow case emits a
  new `CaseDataExtra` class with ONE prefix-less `@JsonUnwrapped private CaseDataExtra caseDataExtra;` on the
  root. **Round-2 residual (now fixed):** `SscsCaseData` sits at exactly the 254-arg constructor edge, so even
  that single added member took it to 255 and the `too many parameters` error persisted — round 2 only warned.
  Round 3: (a) `generatesAllArgsConstructor` now explicitly excludes `@SuperBuilder` (a single builder-arg
  constructor, never a per-field one — fpl's 642-field proof); (b) in the borderline case the fields are nested
  into an **existing** prefix-less `@JsonUnwrapped` member's class instead — chosen deterministically as the
  first-alphabetical member that is neither a `@Builder`-bound-explicit-constructor idiom nor missing a getter and
  has constructor headroom — so **ZERO fields are added to the limited root** (prefix-less unwrapping keeps the
  CCD IDs identical). On the real sscs model the 78 fields nest into `adjournment` (`Adjournment`), documented in
  the patch gap + CLI stdout; the `too many parameters` error is **GONE** (real javac: 0 errors). Falls back to
  `CaseDataExtra` + still-over-limit flag when no member qualifies. Regressions: `SynthesisPlacementTest`
  (`superBuilderNeverOverflows`, `builderWithAllArgsOverflowsToCaseDataExtra`,
  `borderlinePlusOneTipNestsIntoExistingUnwrappedMember`, `borderlineWithNoUsableHostFallsBackToCaseDataExtra`)
  + `RetrofitRoundTripTest.retrofitRoundTripsViaCaseDataExtraWhenConstructorLimitTripped`.
- B3 (sscs) — **FIXED; widened round 3.** `RetrofitPatchEmitter.hasBuilderBoundJsonCreator` detects the
  `@Builder` + hand-written single-arg `@JsonCreator` idiom; such classes are NOT synthesised into — their members
  route to `GapAction.MANUAL_PLACEMENT` (verified: sscs's Bundle/ScannedDocument/AudioVideoEvidence/
  OtherPartyOption etc. all reported). **Round 3** added `hasBuilderBoundExplicitConstructor` so the guard also
  catches a `@Builder` bound to a plain (non-`@JsonCreator`) hand-written constructor — sscs's `Appeal`
  (`@Data @Builder` + an 11-arg `@JsonProperty` constructor), which the A2 fix newly reached. Regression:
  `RetrofitPatchEmitterGoldenTest.doesNotSynthesiseIntoBuilderBoundJsonCreatorClassAndReportsGap`.
- Bug4 (sscs) — **FIXED (round 3, 2026-07-15). New diagnosis — round-1's "38 errors → 3 bugs" undercounted:
  30 of them were `invalid method reference` on `BenefitEventsConfigNN`, never explained.** `SscsCaseData`'s
  `finalDecisionCaseData`/`pipSscsCaseData`/`sscsDeprecatedFields` are prefix-less `@JsonUnwrapped
  @Getter(AccessLevel.NONE)` — Lombok's getter is suppressed and the model hand-writes a differently-named
  accessor (`getSscsFinalDecisionCaseData`, which resolves to a non-existent property) or none. The events emitter
  placed their leaves via `fields.complex(SscsCaseData::getFinalDecisionCaseData)` — a getter that does not exist.
  The SDK's event-field API has no public string-id overload (`field(String)`/`complex(String,Class)` are
  package-private, and SDK `src/main` is off-limits), so **fix:** `ModelSourceIndex.hasResolvableGetter` reports
  whether a name-matching public getter exists; `RetrofitModelRebinder` marks unwrapped leaves with an
  unresolvable parent getter `unplaceableFieldIds`; `EventsConfigEmitter.emitPageFields` skips them with a
  `PASSTHROUGH_COLUMN` gap (per-field metadata carried by `buildEventFieldColumnPassthrough`). On the real sscs
  model this removes all 30 method-reference errors (real javac: 0) and records 46 gaps — no longer silent.
  Regressions: `RetrofitModelRebinderTest.marksUnwrappedLeavesWithNoResolvableParentGetterUnplaceable`,
  `EventsConfigEmitterTest.skipsUnplaceableFieldAndRecordsGapWithoutEmittingABrokenGetter`.

**C. Imports (mechanical)**
- C1 (prl + fpl F2) — **FIXED.** New `ImportBinder` tracks simple names bound per compilation unit
  (existing imports + patch-added); a name already bound to a different type is written
  fully-qualified instead of adding a clashing import. Verified: prl's `OtherDocuments`/`Miam`
  references are FQN-qualified. Regression: `ImportBinderTest` (incl. the SDK-`type.Document`-vs-model
  `Document` case). Covers F2 (the same family at fpl scale).
- C2 (ET + prl) — **NOT A BUG against current source (pilot-fixed; regression added).** Reproduced ET
  with the rebuilt CLI: `RespondentRepresentative`/`ClaimantRepresentative` companions now correctly
  `import uk.gov.hmcts.et.common.model.ccd.types.OrganisationUsersIdamUser` via the pilot's
  `retrofitTypeFqnOverrides` (`ModelSourceIndex.topLevelFqnsOutside`). Regression:
  `ModelSourceIndexTest.mapsUniqueSiblingPackageTypesForCompanionImports`.

**FPL-specific (from a lane killed mid-validation — provisional, re-validate after the fix round)**
- F1 — **FIXED (root cause was a name collision, not enum-body arity).** The pre-fix enum-body
  constructor-arity errors are already gone (generated FixedList enums carry proper `label`/`code`
  backing fields). The real surviving break: a fresh `HearingVenue` enum was generated into the model
  package where a `HearingVenue` **class** (a `@Data` address holder, not an enum) already exists — a
  duplicate-type compile error. `RetrofitModelRebinder` now drops any FixedList whose ID names an
  EXISTING top-level model type of any kind (`ModelSourceIndex.hasTopLevelType`), not just an enum, so
  no colliding enum is generated (`AuthorityFixedList`, with no model type, still generates). Verified
  on the real fpl model. Regression: `RetrofitModelRebinderTest`.
- F2 — **FIXED** by C1 (see above).
- Caveats: the javac experiment's classpath completeness is unknown (6,882 cannot-find-symbol errors
  suggest missing external jars, like other lanes); phase-1/2 numbers and the clean apply are solid.
  The fpl patch root inconsistency is fixed below.
- **Patch-root consistency — FIXED.** New `--model-repo-root` option (default: the source root) makes
  the emitter prepend `--model-source-root` relative to the repo root (e.g. `service/src/main/java/`)
  to every diff path, so all lanes' patches root identically at the repo root. `bin/retrofit-verify`
  now passes `--model-repo-root` and applies from the copied repo root. Regression:
  `RetrofitPatchEmitterGoldenTest.rootsPatchPathsAtTheModelRepoRootWhenPrefixed`.

**D. Resolution (needs a mechanism, not a fix)**
- D1 (civil) — **FIXED.** New repeatable `--type-package-hint TypeName=fully.qualified.package`
  option, consulted by `ModelSourceIndex.topLevelFqnsOutside` before it refuses to guess; an unknown
  hint (no such type in that package) errors clearly (`RetrofitConverter.validatePackageHints`).
  Documented in the README. Regressions: `ModelSourceIndexTest` (drops ambiguous without a hint,
  resolves with one) + `ConvertCommandTest.parsesTypePackageHintsIntoRetrofitOptions`.

**Fixed during the pilot (already in converter source, in main-tree working state):** unescaped
newlines in `@CCD` labels; JavaParser language level vs `sealed` types; `git apply` phantom-EOF
off-by-one (+ `\ No newline` handling); nested-interface complex-type resolution; PascalCase
synthesised-field collisions; definition-only companion complex-type generation + cross-package
member imports.

## Validation levels achieved (honesty ledger)

No lane reached a full service-classpath compile except Civil (the pilot, by design) — external
dependency closures (probate-commons, ET service deps) are not cheaply assemblable outside each
service's own build, which is exactly why decision 5 puts final verification in the service's build
via `bin/retrofit-verify`. What each lane *did* prove is in the scoreboard; sscs and prl achieved
genuine `javac` compiles against gradle-cache-assembled classpaths with baseline controls.

## Civil pilot — Round 2 (2026-07-15): first end-to-end run to the DIFF stage

The rebuilt CLI (with `--type-package-hint`) drove the Civil pilot **all the way to the residual
diff** for the first time — every prior run stalled at compile. `bin/retrofit-verify` now also
forwards `--type-package-hint` (it previously rejected it as an unknown argument).

### Type-package-hint choices (evidence-based)

- **`HearingLength=uk.gov.hmcts.reform.civil.enums.dq`.** The model declares two `HearingLength`
  types: `enums.dq.HearingLength` (an `enum LESS_THAN_DAY/ONE_DAY/MORE_THAN_DAY`) and
  `ga.model.genapplication.HearingLength` (a 3-`int` complex POJO). The definition uses
  `HearingLength` as a **`FixedList`** (`FixedLists/HearingLength.json`, codes
  `LESS_THAN_DAY/ONE_DAY/MORE_THAN_DAY`, referenced via `FieldTypeParameter` on `FixedRadioList`
  fields in `ComplexTypes/DQ/Hearing.json` etc.). A FixedList can only be backed by an enum whose
  constants match those codes — the `enums.dq` enum, byte-for-byte. The genapplication POJO cannot
  back a FixedList. **Decisive, not a guess.**
- **`CaseLocationCivil=uk.gov.hmcts.reform.civil.model.defaultjudgment`.** Two `CaseLocationCivil`
  classes exist: `model.defaultjudgment` (2 fields: `region`, `baseLocation`) and
  `model.genapplication` (5 fields: `region`, `siteName`, `baseLocation`, `address`, `postcode`).
  The definition's `CaseLocationCivil` complex type (`ComplexTypes/CaseLocation.json`) has exactly
  two members — `region` and `baseLocation` — matching the `defaultjudgment` shape exactly; the
  genapplication 5-member shape does not. **Decided by ComplexTypes member shape.**

### Per-stage outcome

| Stage | Round 1 | Round 2 |
|---|---|---|
| convert | ✓ (94.6%) | ✓ **94.7% resolved, 63.8% exact, 250 files** |
| copy | ✓ | ✓ |
| patch (`git apply`) | ✓ | ✓ clean, 250 files |
| generate (`gCC`) | ✗ 20 compile errors | ✓ **BUILD SUCCESSFUL — CIVIL definition emitted** |
| verify (DIFF) | not reached | ✓ **reached — 9,098 residual diff lines** |

### Converter/pipeline bugs found and fixed (round 2)

Five issues blocked reaching the diff; all fixed in the converter/pipeline (**not** in
`sdk/ccd-config-generator/src/main`), each with a regression test. Gates green
(`-p sdk :ccd-definition-converter:check :ccd-config-generator:test
:ccd-definition-converter:roundTripTest`).

- **R2-1 javac `StackOverflowError` (pipeline).** The patched `CaseData` carries 399 original + 740
  synthesised = **1,139 fields**; Lombok's `@Data` `toString()`/`equals()` become one binary
  expression `javac` attributes recursively (`Attr.visitBinary`), overflowing the compiler thread's
  default stack. Fix: `bin/retrofit-verify`'s init script forks `JavaCompile` with `-Xss128m`
  (verified: `-Xss128m` alone turns the crash into a clean compile). Only affects how the copied
  service is compiled here, never the service's own build.
- **R2-2 nested `@JsonUnwrapped` clustering (converter — correctness).** Civil nests
  `CaseData.mediation` (`@JsonUnwrapped Mediation`) → `Mediation.mediationSuccessful`
  (`@JsonUnwrapped MediationSuccessful`). The single-parent `ClusteredFieldRef` emitted
  `Mediation::getMediationSettlementAgreedAt` — a getter that leaf's *outer* parent does not declare
  (the member lives on `MediationSuccessful`), an "invalid method reference" compile error. Fix:
  `PropertyResolver` now records the **full unwrap-hop chain**; `ClusteredFieldRef` carries
  intermediate `.complex()` hops; `EventsConfigEmitter` opens a nested
  `.complex(CaseData::getMediation).complex(Mediation::getMediationSuccessful)
  .mandatory(MediationSuccessful::get…).done().done()` chain (the SDK's `FieldCollectionBuilder`
  supports nested `.complex()` and re-flattens the prefixes). Generate mode's clusterer only ever
  produces single-level refs, so it is unchanged. Regression: the `RetrofitRoundTripTest` fixture
  gained a nested prefix-less unwrap (`HearingData.settlement → SettlementData.settledAmount`) and
  round-trips **byte-identical**. Confirmed on the real Civil output (`mediationSettlementAgreedAt`
  now generates correctly).
- **R2-3 all-args-constructor subclass `super(...)` synthesis break (converter — B4, the B3 family).**
  Synthesising `bandLabel` into `FixedRecoverableCosts` (`@AllArgsConstructor`) widened its all-args
  constructor 5→6 args, breaking subclass `FixedRecoverableCostsSection`'s positional `super(5 args)`
  ("no suitable constructor found"). Fix: `ModelSourceIndex.hasSubtypeWithExplicitSuperCall` detects a
  subclass making a positional `super(...)` call; `RetrofitPatchEmitter` routes such members to a
  `MANUAL_PLACEMENT` gap instead of synthesising. Regression:
  `RetrofitPatchEmitterGoldenTest.doesNotSynthesiseIntoClassWithSubclassPositionalSuperCallAndReportsGap`.
- **R2-4 `@Value`/final-field synthesis break (converter).** `Bundle` is `@Value` (final fields) with
  a hand-written `@JsonCreator` that assigns only `value`; synthesising a final `stitchStatus` left it
  uninitialised ("might not have been initialized"). Fix: `RetrofitPatchEmitter.hasFinalFields`
  (a `@Value` or explicitly-final-field class) + an explicit constructor routes members to a
  `MANUAL_PLACEMENT` gap. Regression:
  `RetrofitPatchEmitterGoldenTest.doesNotSynthesiseIntoValueClassWithHandwrittenConstructorAndReportsGap`.
- **R2-5 generator boots the real service context (converter emission + pipeline).** `Main` runs the
  emitted `@SpringBootApplication`; on the real service classpath Spring stood up the service's
  DataSource/Flyway (no DB), then WebMvc + servlet/reactive OAuth2 client security (unresolved
  `${idam.web.url}`), then component-scanned the model package's own `@Component`/`@Service` beans
  (`InterlocutoryJudgementDocMapper` etc.) and IDAM's `spring.factories` `IdamClient`. The generator
  only needs the SDK + companion-config beans and reflects model classes by type. Fix: `ApplicationEmitter`
  now (a) scans only `uk.gov.hmcts.ccd.sdk` + the config package — **not** the model package; (b)
  `excludeName`s the DataSource/JPA/Flyway/servlet+reactive security/OAuth2-client autoconfigs and
  IDAM's `IdamClient`/`OAuth2Configuration`; the pipeline additionally runs `gCC` with
  `spring.main.web-application-type=none`. Regressions in `ApplicationEmitterTest`
  (`doesNotComponentScanTheModelPackage`, `excludesPersistenceAutoConfigurationsSoNoDatabaseIsRequired`).

### Residual diff buckets (9,098 lines) — the follow-on work

Bucketed by sheet × column/presence (full breakdown in the run log). Ranked by volume; each is a
distinct systematic root cause, none a blocker for reaching the diff:

| Lines | Bucket | Root-cause class |
|---:|---|---|
| 2,454 | `EventToComplexTypes` — rows missing entirely | **SDK-gap**: the generator emits no `CaseEventToComplexTypes` sheet; per-event complex-member placements (`Party.individualDateOfBirth`, `SolicitorReferences.*`, …) are not reproduced. Largest single lever. |
| 1,735 + 360 | `FixedLists` — rows missing / `ListElement` = enum code not label | model enums carry no `ListElement` label metadata; the SDK emits the constant name. Needs `@CCD`/`@JsonProperty`-derived labels on enum constants (synthesis onto enums). |
| ~1,312 | `CaseEvent` `CallBackURL*` / `RetriesTimeout*` (AboutToStart/Mid/AboutToSubmit/Submitted) | retrofit companions don't wire event callback URLs (HMC + spec events). The service defines these on its own event beans, not reproduced by the config-only companions. |
| 960 + 258 + 65 | `CaseEventToFields` `FieldShowCondition` / `CallBackURLMidEvent` / `RetriesTimeoutURLMidEvent` | per-field show-conditions and mid-event callbacks on event fields not carried through. |
| 947 | `ComplexTypes` — rows missing | complex types reached only via definition-only members / unsynthesised classes (B3/B4/@Value gaps above) don't emit their member rows. |
| 241 | `CaseEventToFields` `RetainHiddenValue` | documented residual (no compile-safe SDK overload — see fidelity notes). |
| 186 + 29 | `CaseField` `FieldType`/`FieldTypeParameter` | genuine model-vs-definition type divergences a maintainer reconciles by hand (the A1 class: e.g. `joPaymentPlan` model `JudgmentPaymentPlan` vs definition `JoPaymentPlan`). |
| 114 | `CaseEventToFields` `CaseEventFieldLabel` | per-event field labels not grafted. |
| 98 | `CaseEventToFields` `DisplayContext` | context (READONLY/OPTIONAL/…) mismatches. |
| 19+19+19+18 | `State` `Name`/`Description`/`TitleDisplay` / rows | State enum has no human-label metadata; SDK emits the constant name (`CASE_SETTLED` vs `Case Settled`). GA-application states also appear as unexpected rows. |
| ~120 | remainder | `CaseTypeTab`, `AuthorisationCase*`, `SearchCasesResultFields`, `UserProfile`, `RoleToAccessProfiles`, `SearchCriteria`, `ChallengeQuestion` — small per-sheet tails. |

The residuals are **enumerable and mostly systematic** — three levers (emit `CaseEventToComplexTypes`;
carry enum `ListElement`/State `Name`+`Description` labels; wire event `CallBackURL*`) would clear
~6,000 of the 9,098 lines. The rest are the known permanent divergences (type reconciliations,
`RetainHiddenValue`) already documented in the fidelity ledger.

## Next steps

1. ~~**Central fix round**~~ — **DONE (2026-07-15).** Every consolidated bug worked with a regression
   test; A1 and C2 found to be not-a-bug against current source (A1's report text fixed); D1 and the
   patch-root option implemented; B2 placement per the maintainer decision. Gates green, CLI rebuilt.
   New retrofit sources: `TypeReconciler`, `SynthesisPlacement`, `ImportBinder`.
2. ~~Rebuild the CLI distribution~~ — **DONE** (`installDist`; `--type-package-hint`,
   `--model-repo-root` present).
3. **Re-run every case type through `bin/retrofit-verify`** with the rebuilt CLI — the emitted patches
   changed materially (A2 overrides, B1 skips, B2 CaseDataExtra, B3 gaps, C1 FQN refs, repo-rooted
   paths), so every lane's patch and companion set must be regenerated. ~~Civil first.~~ **Civil DONE
   (round 2, 2026-07-15)** — reached the DIFF stage; hints `HearingLength=…enums.dq` and
   `CaseLocationCivil=…model.defaultjudgment` (evidence above); five more converter/pipeline bugs
   fixed (R2-1…R2-5). Remaining case types still to run.
6. **Civil residual reduction** (9,098 lines, bucketed above). Highest-leverage, roughly in order:
   (a) emit the `CaseEventToComplexTypes` sheet from per-event complex-member placements (~2,454
   lines); (b) carry enum `ListElement` + `State` `Name`/`Description` labels (~2,150); (c) wire event
   `CallBackURL*`/`RetriesTimeout*` (~1,700). The type-reconciliation and `RetainHiddenValue` residuals
   are permanent divergences already in the fidelity ledger.
4. ET: apply the subclass reconciliation (762 base / 31 subclass / 12 policy calls — the 3 structural
   divergences need a maintainer decision).
5. ~~SSCS B2 borderline~~ — **DONE (round 3, 2026-07-15).** `SscsCaseData` sits at the 254-arg edge, so even
   the +1 `CaseDataExtra` member tripped the limit. The converter now nests the synthesised fields into an
   existing prefix-less `@JsonUnwrapped` member's class (`adjournment`/`Adjournment`) — zero fields added to the
   root — and the real-javac compile is clean (0 errors). See RETROFIT-REPORT-Benefit.md. A2 (case-insensitive
   complex-type resolution) and Bug4 (unresolvable unwrapped-parent getters) were fixed in the same round.

## Structural round (2026-07-16): companions restructured to reference-service layout

The companion emission was reshaped to the mature-service idiom (nfdiv/sptribs) — the
adoption-decisive change closing quality-review findings #1/#2/#6:

- **One `@Component CCDConfig` class per event** in `<root>.event`, PascalCase-named from the event
  ID with the ID as a `static final String` constant; the numbered `EventsConfigNN` grab-bags are
  gone (finding #1).
- **One class per wizard page** in `<root>.event.page` with a `public static apply(fields)` the
  event class calls; the `<Page>FieldsN` line-budget fragment survives only for a single page that
  alone overflows the JVM method limit (finding #2).
- **Config split by concern** into `<Prefix>CaseType`/`Grants`/`Tabs`/`WorkBasket`/`Search`/
  `NoticeOfChange`/`RoleToAccessProfiles`/`Categories` beans (finding #6); no `CoreConfig` monolith.
- **Access classes** in `<root>.access`; **grants** use the concise `Permission.CR/CRU/CRUD` set
  shortcuts with varargs roles (finding #5/#11).
- The **root package is derived from `--model-package`** (`…model…` → `…ccd`), overridable with
  `--root-package`, so companions land in the service's main source tree beside the model. The clone
  regeneration writes this tree (untracked), retiring the flat `generated-config/` directory.

Byte-identity across the golden fixtures is proven by `RoundTripTest` (unchanged JSON) and the shape
by `GeneratedLayoutTest`; the seven real-fixture baselines are re-proven at integration.

## Artefacts

- Per-lane reports + patches: `sdk/ccd-definition-converter/retrofit-reports/`
- ET subclass skeletons + divergence classification: same directory
- Pipeline: `sdk/ccd-definition-converter/bin/retrofit-verify` (usage in README)
- Pilot per-stage detail: proposal doc §"Phase 3 status"
