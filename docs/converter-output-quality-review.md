# Converter Output Quality Review

**Scope:** the ccd-definition-converter's emitted artefacts (retrofit companion sources + model diffs, and the generate-mode golden `expected-src`) measured against two mature hand-written config-generator services, **nfdiv-case-api** and **sptribs-case-api**. This is a *quality/adoptability* review, not a correctness review — the round-trip owns correctness.

**Lanes reviewed in depth:** `sscs-common` (Benefit — small) and `civil-service` (CIVIL — huge). Spot-checked: `probate-back-office`, `et-ccd-callbacks`, `fpl-ccd-configuration`, plus the generate-mode goldens.

**Resolved (not re-reported, verified below):** the `AccessNN` explosion + 50-plus opaque access classes has been replaced by the **composition scheme** — every field's residual is a union of named access-group classes (`@CCD(access = {DefaultAccess.class, CaseworkerCruAccess.class})`), built from mined groups + per-role atoms + a dedicated-class fallback, exactly as a hand-written HMCTS model composes access (`AccessClassComputer`, nfdiv-style). The earlier inline-`@Grant` approach was reverted by the maintainer in favour of this composition model. Annotation same-line placement is fixed. Regenerated `generated-config/` clones show the composition output; findings below record where the fix has landed.

---

## Ranked findings

| # | Finding | Evidence (converter vs nfdiv/sptribs) | Severity for adoption | Recommended action | Effort |
|---|---------|----------------------------------------|----------------------|--------------------|--------|
| 1 | **Events emitted as chunked `EventsConfigNN` grab-bags of 40 events each, not one class per event** | `BenefitEventsConfig01..07`, `CIVILEventsConfig01..23` (40 events/class) vs nfdiv/sptribs strict **one `@Component` per event** (`ConfirmReceipt.java`, `CaseworkerAddNote.java`) | **High** — **RESOLVED** | converter-emitter change: emit one class per event (fall back to a helper only past the method-size limit) — **landed (EventsConfigEmitter: one PascalCase `@Component` per event in `<root>.event`, event ID as a `static final String` constant)** | Large |
| 2 | **Large events fragmented into `CIVILEvent_<NAME>_FieldsN.apply(fields)` static helper classes** | `CIVILEvent_CREATE_SDO_Fields1/2/3` (3 files, 581 lines) reassembled by `CREATE_SDO(...)` vs sptribs/nfdiv `CcdPageConfiguration` **page-per-concern** classes with meaningful names (`SolAboutTheSolicitor`, `SubjectDetails`) | **High** — **RESOLVED** | converter-emitter change: split by CCD *page* into named page classes, not by arbitrary line budget — **landed (one `<Event><Page>Page` class per wizard page in `<root>.event.page` with a `public static apply(fields)`; the `<Page>FieldsN` line-budget split survives only for a single page that alone overflows the JVM method limit)** | Large |
| 3 | **Lowercase-initial generated companion classes** violate Java naming and will not pass most linters | `sscs .../domain/benefit.java`, `doc.java`, `otherPartySelection.java` (58 lowercase-initial classes in one package) vs every hand-written model class PascalCase | **High** — **RESOLVED** | converter-emitter change: PascalCase the class, keep the definition ID via `@ComplexType(name=)` — **landed (TypeClassNamer + ComplexTypeEmitter)** | Small |
| 4 | **`FL_`-prefixed fixed-list enums** with a machine prefix no human would write | `FL_comparedToDWP`, `FL_withDwpWorkflow` (158 in sscs) vs nfdiv/sptribs enums named for the domain (`ApplicationType`, `SupplementaryCaseType`) | **Medium-High** — **RESOLVED** | converter-emitter change: derive a domain name (drop `FL_`, PascalCase, collision-suffix); list ID round-trips via `@ComplexType(name=)` — **landed; needs the paired SDK carrier (see note below)** | Medium |
| 5 | **`AccessNN` numbered classes + a fat `@CCD(access={AccessNN.class})` import wall on the model** | `SscsCaseData.java` diff adds ~90 `import ...Access13;` lines; `Access2`/`Access50` opaque vs nfdiv `SolicitorAccess`, `CaseworkerAndSuperUserAccess` (semantic, 33 total) | **High** — **RESOLVED** | composition scheme: mined groups (`DefaultAccess` + content-named) + per-role atoms, no `AccessNN` — **assessed below: landed** | — |
| 6 | **`CoreConfig` is one 1,400–1,900-line `configure()` monolith** — state grants + every tab + workbasket + search all inline | `BenefitCoreConfig` (1,454 lines), `CIVILCoreConfig` (1,895) vs nfdiv/sptribs split into `CaseTypeTab`, `SearchInputFields`, `RoleToAccessProfiles`, `WorkBasketInputFields` as separate `CCDConfig` beans, each delegating to small `buildXxxTab` helpers | **Medium** — **RESOLVED** | converter-emitter change: emit separate `*Tab` / `*Search` / `*Workbasket` / `*RoleToAccessProfile` classes — **landed (CoreConfigEmitter now emits `<Prefix>CaseType`/`Grants`/`Tabs`/`WorkBasket`/`Search`/`NoticeOfChange`/`RoleToAccessProfiles`/`Categories` as separate `@Component CCDConfig` beans, each emitted only when its concern carries content; `CaseType` always)** | Medium |
| 7 | **State grants written as one flat `builder.grant(...)` per (state,role,perm) line** — no grouping, no access-class reuse | `BenefitCoreConfig` lines 36–227 (~190 individual `.grant()` calls) vs nfdiv state access expressed once via `@CCD(access={DefaultStateAccess.class})` on the `State` enum constant | **Medium** — **DEFERRED** | SDK-idiom adoption: emit state-level access via `@CCD(access=...)` on generated `State` constants where the grant matrix is shared — **not landed; see note below** | Medium |
| 8 | **Field predicates carry trailing `null, null, null, null` positional args** where a human uses the terse overload | `fields.mandatory(getX, "cond", null, null, null, null)` (sscs `BenefitEventsConfig01:200`) vs nfdiv `.mandatory(Solicitor::getEmail)` / `.optional(x, NEVER_SHOW)` | **Medium** | converter-emitter change: pick the shortest overload that fits; omit trailing nulls | Small |
| 9 | **Raw page-id string literals with no constants and machine-ish values** (`"1.0"`, `"2.0"`, `"selectHearing"`) | sscs `fields.page("1.0")` vs nfdiv/sptribs `.page("SolAboutTheSolicitor", this::midEvent)` (semantic, sometimes constant-backed) | **Low-Medium** | inherent-to-generation (page ids come from the definition) — accept, but see verdict | — |
| 10 | **No orienting narrative; every class stamped `do not edit by hand`** — the opposite of a file a team owns | Uniform `Generated by ccd-definition-converter — do not edit by hand` header vs hand-written files with no such banner and self-documenting structure | **Medium** (adoption blocker: signals "not yours") — **RESOLVED** | retrofit-patch/emitter change: drop or soften the banner on artefacts intended for adoption — **landed (EmitContext.banner: retrofit → ownable provenance note, generate → banner kept)** | Small |
| 11 | **`explicitGrants()` + per-event `.grant(...)` walls** repeat the full role matrix inline on every event | sscs `dwpActionDirection` lists 15 `.grant(...)` lines; civil similar, vs nfdiv `.grant(CREATE_READ_UPDATE, SOLICITOR, APPLICANT_2_SOLICITOR, CASE_WORKER).grantHistoryOnly(...)` (varargs roles, named permission sets) | **Medium** — **RESOLVED** | converter-emitter change: use varargs-role `.grant(perms, roles...)` and, for grant maps shared by ≥3 events, a named `<Prefix>EventGrants` helper — **landed (EventsConfigEmitter)** | Medium |
| 12 | **`AccessNN` renumbering is diff noise that swamps real signal** | et-ccd DIVERGENCE doc: raw diff "2341 lines / 508 hunks but almost all of it is Access-class renumbering"; had to be stripped to see the 12 genuine divergences | **Medium** (maintainability) — **RESOLVED** | resolved by finding #5's composition scheme (deterministic content-derived names; no per-run renumbering) | — |
| 13 | **No typed getters in `CoreConfig` tabs/search — all string field IDs** | `BenefitCoreConfig.tab(...).field("summaryName")` — *however* this **matches** nfdiv/sptribs, which also use string IDs in tabs/search | **None** (converter matches house style here) | accept | — |

---

## Per-finding detail

### 1. Chunked `EventsConfigNN` grab-bags vs one class per event — **High — RESOLVED**

**Status: landed.** `EventsConfigEmitter` now emits exactly **one `@Component implements CCDConfig` class per event**, PascalCase-named from the event ID (`createCase` → `CreateCase`, `boHandleEvidence` → `BoHandleEvidence`) into `<root>.event`, with the CCD event ID declared as a `public static final String` constant (`CREATE_CASE = "createCase"`) that `configure()` references — the nfdiv idiom. Class names are allocated collision-free against a shared used-name set, so two IDs that PascalCase to the same token deterministically suffix. The numbered `EventsConfigNN` grab-bags and their private-method dispatch lists are gone, and with them the `--events-per-config` chunking (the option remains on the CLI as an accepted no-op for compatibility). Proven by `RoundTripTest` (JSON byte-identity), `GeneratedLayoutTest` (file tree) and `EventsConfigEmitterTest`.

The single largest structural gap. Both reference teams treat **one event = one class**, and it is not incidental — it is how they navigate, review, and assign ownership. nfdiv has 276 `implements CCDConfig` classes, sptribs 73, each a `@Component` whose whole job is one event.

nfdiv `common/event/ConfirmReceipt.java` — the entire class:

```java
@Component
public class ConfirmReceipt implements CCDConfig<CaseData, State, UserRole> {
    public static final String CONFIRM_RECEIPT = "confirm-receipt";

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(CONFIRM_RECEIPT)
            .forStates(Holding, AwaitingJsNullity)
            .showCondition("applicationType=\"jointApplication\"")
            .name("Confirm Receipt")
            .grant(CREATE_READ_UPDATE, SOLICITOR, APPLICANT_2_SOLICITOR, CASE_WORKER)
            .grantHistoryOnly(SUPER_USER, LEGAL_ADVISOR, JUDGE));
    }
}
```

The converter instead emits a `BenefitEventsConfig01` whose `configure()` is a 40-line dispatch list, followed by 40 private methods:

```java
public class BenefitEventsConfig01 implements CCDConfig<SscsCaseData, State, UserRole> {
    @Override
    public void configure(ConfigBuilder<...> builder) {
        directionDueToday_nonprod(builder);
        reviewBfDateRequired_nonprod(builder);
        hearingToday_nonprod(builder);
        // ... 37 more
    }
    private void directionDueToday_nonprod(ConfigBuilder<...> builder) { ... }
```

To find one event a maintainer must know which of `BenefitEventsConfig01..07` (or `CIVILEventsConfig01..23`) holds it — the numbering is packing order, carrying no meaning. Adding an event means editing a shared file and its dispatch list, guaranteeing merge contention (the exact thing per-event classes were adopted to avoid). **Cost of fixing:** the per-event method bodies already exist verbatim — promoting each to its own `@Component` class is largely mechanical (class-per-method + import hoisting). The only real decision is the fallback when a single event's `configure()` would exceed the JVM method-size limit — which is exactly what finding #2 is about.

### 2. `Event_<NAME>_FieldsN` line-budget fragments vs page-per-concern classes — **High — RESOLVED**

**Status: landed.** Each wizard page of a **multi-page** event is now emitted as its own `final` class in `<root>.event.page`, named `<Event><Page>Page` (`CreateCasePage1`, `CreateCaseOrderTypePage`) with a `public static void apply(FieldCollectionBuilder fields)` the event class calls in order after `.fields()`. This is the page-per-concern decomposition both reference teams use (their `CcdPageConfiguration.addTo(pageBuilder)` is the same shape); every page's serializable getter lambdas land in its own class's synthesised `$deserializeLambda$`, so the split also keeps each class under the 64&nbsp;KB bytecode cap. A **single-page** event carries no page class at all — its one page's header (`page(id)`, optional page label / show condition) and field chain are inlined straight into the event class's `configure()` after `.fields()`, since a page class would be pure indirection for one page; a case type whose every event is single-page therefore produces no `event/page/` package. The arbitrary `<Event>_FieldsN` line-budget fragment survives **only** for a single page whose own placements exceed the per-method limit (prl's `waManageOrders`), where it splits *within* that one page's package — the documented overflow (and even then the single-page event still has no page class, only its `FieldsN` fragments). Proven by `RoundTripTest` + `GeneratedLayoutTest` + `EventsConfigEmitterTest`.

For big events the converter *used to* split *field placements* across arbitrarily-numbered static helpers and re-glue them:

`CIVILEventsConfig13.CREATE_SDO(...)`:

```java
        .fields();
    CIVILEvent_CREATE_SDO_Fields1.apply(fields);
    CIVILEvent_CREATE_SDO_Fields2.apply(fields);
    CIVILEvent_CREATE_SDO_Fields3.apply(fields);
```

`CIVILEvent_CREATE_SDO_Fields1` is a `final` class with a private ctor and one `static apply(FieldCollectionBuilder...)`. The split point is a line budget: `Fields1` ends mid-event and `Fields2` continues. The pages *are* right there in the data — `Fields1` alone contains `fields.page("SDO")`, `page("ClaimsTrack")`, `page("OrderType")`, `page("DisposalHearing")`. The converter had the natural seam (the CCD page) and split on line count instead.

sptribs does exactly the page-per-concern decomposition the converter is missing — `SubjectDetails implements CcdPageConfiguration`:

```java
pageBuilder.page("subjectDetailsObject", this::midEvent)
    .pageLabel("Who is the subject of this case?")
    .complex(CaseData::getCicCase)
        .mandatory(CicCase::getFullName)
        .mandatory(CicCase::getEmail, "cicCaseContactPreferenceType = \"Email\"")
    .done();
```

**Recommendation:** when an event is too large for one method, split by `page(...)` into `<Event><PageLabel>Page` classes (or `CcdPageConfiguration`-style `addTo`), not `FieldsN`. This makes each generated fragment a named, reviewable unit and matches both reference teams.

### 3. Lowercase-initial companion classes — **High, cheap — RESOLVED**

**Status: landed.** `TypeClassNamer` PascalCases every derived companion class/enum name and allocates it collision-free against a shared used-name set (seeded with `CaseData`/`State`/`UserRole`). `ComplexTypeEmitter` names the class from the PascalCase name and preserves the CCD type ID via `@ComplexType(name = id)` when it differs; `ComplexTypeGenerator` already reads that name for the wire ID, so round-trip is byte-identical. `class benefit` → `class Benefit`, `class otherPartySelection` → `class OtherPartySelection`.

In **retrofit** mode the used-name set is additionally seeded from the team's existing model so a PascalCased companion cannot collide with an unrelated existing type of the same name in the same package (pre-rename the lowercase `benefit` coexisted with the domain enum `Benefit`; post-rename it would be a `duplicate class`). The seeding is kind-aware: a complex-type companion (a class) reserves existing model **enum** names only (a complex type matching a model class binds to it and emits no companion, so reserving classes would wrongly suffix the bound reference); a fixed-list companion (an enum) reserves **all** existing model names (it reuses a model enum only on an exact list-ID match, so an `FL_`-prefixed or case-shifted ID still emits and can clash with either kind). Colliding companions are deterministically suffixed (`Benefit` → `Benefit2`) with the CCD wire ID preserved via `@ComplexType(name)`, so baselines stay byte-identical. Verified by `bin/retrofit-verify sscs`: companion + model now compile with zero `duplicate class` errors (was 32).


The converter emits complex-type companions whose class name is the raw camelCase definition ID:

`sscs .../ccd/domain/benefit.java`:

```java
@ComplexType(generate = true)
public class benefit {
    @CCD(label = "Benefit Code", showCondition = "code=\"DO_NOT_SHOW\"")
    private String code;
}
```

`class benefit`, `class doc`, `class otherPartySelection` — 58 lowercase-initial classes in the sscs domain package alone. No hand-written model in either reference service does this; it breaks `TypeName` lint rules and reads as a bug. The RETROFIT-REPORT itself documents the fallout: because `ModelSourceIndex` did a case-sensitive lookup, camelCase IDs failed to resolve to their real PascalCase classes and spurious lowercase companions were minted. The fix landed for *resolution*, but the *generate-a-new-companion* path still names the class after the raw ID. **Fix:** PascalCase the emitted class name and preserve the wire ID with `@ComplexType(name="benefit")` / `@JsonProperty` — the same pattern the SDK already uses for `@JsonProperty("some_field_id")` in the golden `CaseData` (`private String someFieldId;`).

### 4. `FL_`-prefixed enum names — **Medium-High — RESOLVED**

**Status: landed (converter + a paired SDK carrier).** `TypeClassNamer.fixedListName` drops a leading `FL_` machine prefix and PascalCases, collision-suffixing deterministically; `EnumEmitter` names the enum from that and preserves the CCD list ID via `@ComplexType(name = id, generate = true)` when it differs. `FL_comparedToDWP` → `enum ComparedToDWP`.

> **SDK port required (flag for the PR).** Unlike complex types, the SDK's `FixedListGenerator` derived the FixedLists sheet **ID and output file name** straight from `enum.getSimpleName()` with *no* annotation carrier — so renaming the enum class *would* have changed the emitted JSON. `@CCD(typeParameterOverride)` cannot fix this: it is a *field* annotation read by `CaseFieldGenerator` for a referencing field's `FieldTypeParameter`; it cannot set the FixedLists sheet's own ID/file name. A minimal, additive SDK change was therefore required and **kept**: `FixedListGenerator` and `CaseFieldGenerator` now read `@ComplexType(name)` on a `generate = true` enum for the emitted list ID / `FieldTypeParameter` (making enums symmetric with the complex-type path, which already reads `complex.name()`). It is **inert for existing consumers** — the only pre-existing enums carrying `@ComplexType` (`YesOrNo`, `ChangeOrganisationApprovalStatus`, `FlagVisibility`) are all `generate = false`, so they never reach the new branch. Golden-pinned by `E2EConfigGenerationTests#preservesRenamedFixedListId` (case type `RenamedFixedList`). **This SDK change must be ported alongside the converter changes.**


Fixed lists become `FL_comparedToDWP`, `FL_withDwpWorkflow`, `FL_postHearingReviewType` — 158 of them in sscs. The `FL_` is a generator artefact; the enum body is clean and idiomatic:

```java
@Getter @AllArgsConstructor
public enum FL_comparedToDWP implements HasLabel {
    HIGHER("Higher", "higher"),
    SAME("Same", "same"),
    LOWER("Lower", "lower");
}
```

Compare nfdiv's `ApplicationType`, `SupplementaryCaseType` — named for the concept, referenced from `@CCD(typeParameterOverride = "ApplicationType")`. Note the generate-mode golden already does the right thing (`ClaimType`, `YesOrNoChoice` — no prefix), so the retrofit `FL_` prefix is a retrofit-path divergence from the converter's own better generate-mode behaviour. **Fix:** derive the name from the definition ID (strip prefix, PascalCase); use a suffix/prefix only to break a genuine collision.

### 5. `AccessNN` + import wall — **High — RESOLVED (composition scheme)**

Pre-fix output: `SscsCaseData.java` gained ~90 `import uk.gov.hmcts.reform.sscs.ccd.config.Access13;`-style lines, and fields read `@CCD(label = "Appeal", access = {Access11.class})`. `Access2.java`/`Access50.java` were opaque — you had to open the class to learn it meant "CRUD for caseworker + judge + superuser". nfdiv's equivalent is `@CCD(access = {DefaultAccess.class, Applicant2Access.class})` — the name *is* the documentation, and there are 33 classes total, not 150+.

**The composition scheme lands this and matches the nfdiv shape.** `AccessClassComputer` expresses every field's residual as a **union of named access-group classes**: per-role **atoms** (`CaseworkerCruAccess`, `CitizenRAccess`) plus **mined groups** — frequently co-occurring atom-sets carved out greedily, the most-used named `DefaultAccess` and the rest content-derived, gated on ≥ 3 fields / ≥ 2 atoms. The two concerns raised against the earlier inline-`@Grant` design are addressed by construction:
- **No mega-names.** A field composes several *short* class names rather than one giant token; the per-field array is capped at 6 classes (`MAX_CLASSES_PER_FIELD`), and a residual that would need more falls back to *one* dedicated semantically-named class — never `AccessNN`, never a wall of role tokens. Names over 70 chars truncate to first-role + count + digest.
- **No verbose inline blocks.** The inline-`@Grant` path was reverted; a many-role residual is now a readable `access = {DefaultAccess.class, JudgeCruAccess.class, …}` union (or a single dedicated class past the cap), not a 12-line `grants={...}` block.

Determinism (finding #12): names derive from grant content sorted by role, independent of emit order, so the same residual yields the same class name across runs and case types — the `DefaultDefinitionLinkerTest` composition cases lock this in, and the round-trip baselines prove the resolved `AuthorisationCaseField` is byte-identical.

### 6. `CoreConfig` monolith — **Medium — RESOLVED**

**Status: landed.** `CoreConfigEmitter` no longer emits a single `<Prefix>CoreConfig`. It splits the config surface by concern into separate `@Component CCDConfig` beans, mirroring the reference teams' distinct files: `<Prefix>CaseType` (case-type/jurisdiction identity, definition flags, banner and `explicitStateGrants()`), `<Prefix>Grants` (state + complex-type grants), `<Prefix>Tabs`, `<Prefix>WorkBasket`, `<Prefix>Search` (search input/result, search-cases, criteria and parties), `<Prefix>NoticeOfChange`, `<Prefix>RoleToAccessProfiles` and `<Prefix>Categories`. Each is emitted only when its concern carries content; `CaseType` is always emitted (it holds the sole `caseType(...)`/`jurisdiction(...)` call). The SDK aggregates every `CCDConfig` bean onto one builder before generating, so the split is behaviourally identical — proven by `RoundTripTest`. Verified by `GeneratedLayoutTest` and `CoreConfigEmitterTest`.

`BenefitCoreConfig.configure()` was 1,454 lines doing everything: `caseType`, `jurisdiction`, ~190 state `.grant()` calls, ~50 `builder.tab(...)` chains, `workBasketInputFields`, `workBasketResultFields`, `searchInputFields`, `roleToAccessProfile`. `CIVILCoreConfig` was 1,895 lines. Both reference teams split these into separate discoverable `CCDConfig` beans: nfdiv has `CaseTypeTab`, `ApplicationTab`, `SearchInputFields`, `SearchResultFields`, `WorkBasketInputFields`, `RoleToAccessProfiles` as distinct files, each with small `buildXxxTab` helpers.

### 7. Flat state-grant lists vs `@CCD` on `State` — **Medium — DEFERRED**

**Status: not landed (deliberately deferred).** The SDK's `AuthorisationCaseStateGenerator` *does* read per-state `@CCD(access = {...})` (lines 61–74), so the idiom is feasible. It was deferred rather than shipped in this naming round because a correct, deterministic implementation must reuse `AccessClassComputer` on the per-state grant matrices with a **separate name namespace** — two independent derivations (field-level and state-level) would each mint a `DefaultAccess` of different content and collide — which is a refactor of a delicately round-trip-tested class and the largest threat to the byte-identical baseline invariant this round guards. It is the state-level analogue of #5 and should ship as its own change with its own regression coverage. This finding is orthogonal to the naming/idiom fixes (#3/#4/#10/#11) that did land.


```java
builder.grant(State.APPEAL_CREATED, Set.of(Permission.R), UserRole.GS_PROFILE);
builder.grant(State.APPEAL_CREATED, Set.of(Permission.R), UserRole.CASEWORKER_RAS_VALIDATION);
// ~190 more
```

nfdiv attaches state access declaratively to the enum constant instead:

```java
@CCD(label = "20 week holding period", hint = CASE_TITLE, access = {DefaultStateAccess.class})
Holding,
```

Where a state's grant matrix recurs (it does — the reference teams factored `DefaultStateAccess`, `PreSubmissionStateAccess`), the converter could emit shared state-access classes and annotate the generated `State` constants, collapsing ~190 lines to a handful of annotations. **This is the state-level analogue of finding #5's field-level fix** and should reuse the same `AccessClassComputer` machinery.

### 8. Trailing positional `null` args — **Medium, cheap**

```java
fields.mandatory(SscsCaseData::getDirectionTypeDl, "[STATE]=\"doNotUse\"", null, null, null, null);
```

vs nfdiv `.optional(OrganisationPolicy::getOrgPolicyReference, NEVER_SHOW)` / `.mandatory(Solicitor::getEmail)`. The converter always reaches for the widest overload and pads with `null`. **Fix:** select the narrowest overload that expresses the non-null args — a pure emitter change, no behaviour difference.

### 9. Machine page IDs — **Low-Medium, largely inherent**

`fields.page("1.0")`, `page("2.0")` come straight from the definition's `CaseEventToFields` PageID column, so the *value* is inherent to generation. But note some are meaningful (`page("selectHearing")`, `page("appealDetails")`) — where the definition supplied a semantic id the converter preserves it. This is acceptable; the reader's real orientation problem is #1/#2 (which class am I in), not the page id itself.

### 10. `do not edit by hand` banner on adoption artefacts — **Medium — RESOLVED**

**Status: landed.** `EmitContext.banner(definition, sheetClause)` is the single banner source for every emitted type. In **retrofit** mode it returns an ownable provenance line — `Generated by ccd-definition-converter from <definition> on migration; owned by this service team.` — with no "do not edit". In **generate** mode (regenerated each build, converter test fixtures) it keeps the traditional `Generated by ccd-definition-converter [from <sheet>] — do not edit by hand.` banner.


Every generated file carries `Generated by ccd-definition-converter — do not edit by hand.` For *generate-mode* (regenerated each build) that is correct. For *retrofit* output that a team is meant to **adopt and own**, the banner actively signals "this isn't yours, don't touch it" — the opposite of the goal. The reference files carry no such banner. **Fix:** on retrofit-adoption artefacts, drop the banner (or replace with a one-time provenance note), so the code reads as ownable.

### 11. Repeated per-event grant walls — **Medium — RESOLVED**

**Status: landed.** `EventsConfigEmitter` now groups an event's grants by permission set and emits one varargs-role `.grant(permSet, UserRole.A, UserRole.B, …)` call per set. Where an identical grant map is shared by three or more events (`GRANT_HELPER_MIN_EVENTS`), it is factored into a deterministically-named static helper on a per-case-type `<Prefix>EventGrants` class (e.g. `caseworkerCruGrants(event)`), which wraps the event header and returns the same builder for the `.fields()`/statement continuation. Naming is content-derived and usage-ranked, so it is stable across runs.


sscs `dwpActionDirection` inlines 15 `.grant(Set.of(...), UserRole.X)` lines; the same role matrix repeats across dozens of events. nfdiv compresses this with varargs roles + named permission sets: `.grant(CREATE_READ_UPDATE, SOLICITOR, APPLICANT_2_SOLICITOR, CASE_WORKER).grantHistoryOnly(SUPER_USER, LEGAL_ADVISOR, JUDGE)`. The SDK supports the varargs form. **Fix:** group roles by permission set and emit a `Permissions`-style constants class (`CREATE_READ_UPDATE = Set.of(C,R,U)`), matching both reference teams.

### 12. Access renumbering as diff noise — **Medium, resolved by #5**

The et-ccd DIVERGENCE doc had to strip `access = { AccessNN.class }` before it could compare two case types, because per-run renumbering produced 508 hunks of pure churn masking 12 real divergences. Content-derived stable names (finding #5's fix) resolve this **only if** the names are deterministic across runs and across case types — worth an explicit regression test (same residual map → same class name regardless of emit order).

### 13. String field IDs in tabs/search — **no gap**

Worth stating explicitly so it isn't mistaken for a defect: `BenefitCoreConfig`'s tabs use `.field("summaryName")` string IDs rather than typed getters. **This matches the house standard** — both nfdiv (`tab/ApplicationTab`) and sptribs (`tab/CaseTypeTab`) reference tab/search fields by string ID (via `CaseFieldsConstants`), reserving typed getters for event *pages*. The converter is idiomatic here; do not "fix" it.

---

## Verdict — what stands between this output and "a team would happily own this code"

The converter reproduces the *content* faithfully and its per-field `@CCD` annotations, enum bodies, and access-class shapes are already close to hand-written idiom (the generate-mode goldens are essentially indistinguishable from nfdiv). The macro-structure gap has now been closed:

1. **One class per event (#1) and page-per-concern splitting (#2) — RESOLVED.** This was the decisive adoptability gap. Each event is now a findable, named, individually-ownable `@Component` class in `<root>.event`; a multi-page event's wizard pages each get their own class in `<root>.event.page` referenced via `apply(fields)`, while a single-page event inlines its one page directly into the event class (no page class) — the reference-service idiom. The generated code lands in the service's **main source tree** at a package derived from the model package (`uk.gov.hmcts.probate.model…` → `uk.gov.hmcts.probate.ccd`, overridable via `--root-package`).

2. **Naming that reads as machine output (#3 lowercase classes, #4 `FL_`, #5 `AccessNN`) — RESOLVED.** The composition access scheme and the PascalCase/`FL_`-stripping renames landed in the prior naming round; grant emission now uses the concise `Permission.CR/CRU/CRUD` set shortcuts with varargs roles (#5/#11).

3. **The `CoreConfig` monolith (#6) — RESOLVED.** The config surface is split into `<Prefix>CaseType`/`Grants`/`Tabs`/`WorkBasket`/`Search`/`NoticeOfChange`/`RoleToAccessProfiles`/`Categories` beans, in line with both reference teams. (#7, state access via `@CCD` on the `State` enum, remains the separately-tracked deferred item.)

4. **The "do not edit by hand" banner (#10) — RESOLVED** in the naming round (retrofit artefacts carry an ownable provenance note).

With #1, #2, #3, #4, #5, #6, #10 addressed and the composition #5 fix proven on the big case types, the retrofit output crosses from "clearly generated, tolerable to import" to "a team would recognise it as their own house style and maintain it directly." The remaining open items are #7 (state-access `@CCD`, deferred — needs the shared `AccessClassComputer` namespace work) and #8/#9 polish.

**Classification summary:** #1,#2,#3,#4,#5,#6,#11 = converter-emitter changes (**all landed**) · #10 = retrofit-patch/emitter change (**landed**) · #7,#12 = #12 resolved by #5's deterministic names, #7 deferred (state-level analogue of #5) · #8 = terser-overload polish (open) · #9,#13 = inherent/accept.
