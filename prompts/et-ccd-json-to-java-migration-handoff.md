# ET CCD JSON-to-Java migration status and handoff

This is the living resumption document for the ET CCD JSON-to-Java migration. Read it before starting another slice and
update it whenever a slice, migration capability, metric or known blocker changes. The architecture plan is the stable
design; this file records the current implementation state and why it looks the way it does.

Last reviewed: 14 July 2026.

## Resume here

The migration spans the generator repository and its ET submodule:

| Repository | Branch | Current reviewed state |
| --- | --- | --- |
| `hmcts/dtsse-ccd-config-generator` | `json-to-java` | Slice 3 exact Listings conversion in this commit |
| `hmcts/et-ccd-callbacks` | `json-to-java-migration` | `2241a7da6` — Slice 3 exact Listings conversion |

The root repository must point at the intended ET commit. A fresh session should inspect both worktrees before changing
anything:

```shell
git status -sb
git submodule status test-projects/et-ccd-callbacks
git -C test-projects/et-ccd-callbacks status -sb
```

When checking out the published work on a fresh clone, initialise the submodule and put it on its migration branch rather
than leaving it detached:

```shell
git switch json-to-java
git submodule update --init test-projects/et-ccd-callbacks
git -C test-projects/et-ccd-callbacks fetch origin
git -C test-projects/et-ccd-callbacks switch json-to-java-migration
```

Do not pull, reset, clean or restore files until the status of both worktrees has been inspected. Existing local changes
belong to the user.

Read the migration material in this order:

1. this status and handoff;
2. [`et-ccd-json-to-java-architecture-plan.md`](et-ccd-json-to-java-architecture-plan.md);
3. [`et-ccd-json-to-java-style-guide.md`](et-ccd-json-to-java-style-guide.md);
4. [`et-ccd-json-to-java-convergence-tooling.md`](et-ccd-json-to-java-convergence-tooling.md);
5. [`../test-projects/et-ccd-callbacks/ccd-definitions/tools/et-migration-progress/README.md`](../test-projects/et-ccd-callbacks/ccd-definitions/tools/et-migration-progress/README.md); and
6. [`../docs/testing-strategy.md`](../docs/testing-strategy.md).

## Current convergence

The immutable initial baseline is 52,227 remaining differences across `cftlib` and `prod`. The committed snapshot after
the third slice is:

| Metric | Current value |
| --- | ---: |
| Exact Java rows | 2,448 |
| Changed rows | 0 |
| Unexpected rows | 0 |
| Remaining differences | 49,779 |
| Completed differences | 2,448 |
| Completion | 4.69% |
| ET production/generation Java delta | +2,004 |
| SDK production Java delta | +829 |
| Total production Java delta | +2,833 |
| Verification Java delta | +642 |
| Production lines per completed difference | 1.16 |

The authoritative values are in
`test-projects/et-ccd-callbacks/ccd-definitions/migration-progress.json`. If this table and the snapshot disagree, the
snapshot wins and this handoff must be corrected in the same change.

Verify the committed state from the root repository with:

```shell
./gradlew :et:etMigrationProgress
```

This generates Java-only JSON and XLSX artefacts. It does not start ET, wire callbacks or alter the golden definition.

## Reviewed definition changes

None to date. Future entries must identify the exact canonical row delta, commit-pinned platform source references and
version mapping, focused behavioural evidence, security conclusion, affected ET and Java commits, and convergence
metric effect. An unproven candidate does not belong in this ledger.

## Milestone history

| Milestone | Root commit | ET commit | Result |
| --- | --- | --- | --- |
| Migration guide | `c2e1bb86` | — | Established golden-parity and typed-Java rules |
| Convergence baseline | `f0de33c4` | `af0ed98be` | Established 52,227 differences and Java LOC tracking |
| Architecture | `89c94b4f` | — | Chose eight aggregates, composition and explicit profiles |
| Slice 1: `Pre_Hearing_Deposit` | `4253eb3e` | `5cd2c9b6d` | Added 312 exact rows; reached 0.60% |
| Tooling resumption prompt | `ebc0e30e` | — | Documented how to run and interpret convergence |
| Slice 1 generator-fit follow-up | `c343f2c7` | `b0bb67f97` | Replaced repeated field access with a typed class default |
| Slice 2: remainder of `ET_Admin` | `c58ca56c` | `3d551154d` | Added 298 exact rows; reached 1.17% |
| Slice 2 generator-fit follow-up | this review commit | `bb666f5e5` | Replaced magic empty event labels with typed omission metadata |
| Slice 3: paired regional Listings | this exact-conversion commit | `2241a7da6` | Added 1,838 exact rows; reached 4.69% |

The ET submodule commit must be published before a root commit which points to it. Otherwise another checkout cannot
resolve the root tree. Commit ET changes first, then commit the updated submodule pointer and related SDK or prompt
changes in the root repository. Push in the same order.

## Publication state

Both migration branches are published. As of 14 July 2026, neither branch has a pull request. Confirm the current state
rather than assuming this remains true:

```shell
gh pr list --repo hmcts/et-ccd-callbacks --head json-to-java-migration --state all
gh pr list --repo hmcts/dtsse-ccd-config-generator --head json-to-java --state all
```

When pull requests are opened, review and merge the ET pull request first. The generator pull request records an ET
submodule commit, so it must not reach the generator repository's default branch before that commit has reached the ET
default branch. Do not delete the ET migration branch while the generator pull request still depends on it.

## Completed slice ledger

### Slice 1: `Pre_Hearing_Deposit`

Status: complete and exact in both `cftlib` and `prod`.

The slice owns 156 rows per environment:

| Workbook sheet | Exact rows per environment |
| --- | ---: |
| `Jurisdiction` | 1 |
| `CaseType` | 1 |
| `CaseField` | 35 |
| `ComplexTypes` | 3 |
| `State` | 1 |
| `CaseEvent` | 1 |
| `CaseEventToFields` | 33 |
| `CaseTypeTab` | 34 |
| `SearchInputFields` | 1 |
| `SearchResultFields` | 1 |
| `WorkBasketInputFields` | 1 |
| `WorkBasketResultFields` | 6 |
| `AuthorisationCaseType` | 1 |
| `AuthorisationCaseField` | 35 |
| `AuthorisationCaseEvent` | 1 |
| `AuthorisationCaseState` | 1 |
| **Total** | **156** |

Important implementation choices:

- `PreHearingDepositData` remains the runtime wire model; there is no definition-only duplicate.
- `PreHearingDepositDefinition` is in the generation-only `ccdMigration` source set.
- `ImportFile` is a generated complex type; the existing ET `Document` DTO maps to CCD's built-in `Document` type and
  is not emitted as another complex type.
- `Open`, `caseworker-employment-api` and all CRUD policies retain their exact external identifiers.
- The existing `${ET_COS_URL}/admin/preHearingDeposit/createPHRDeposit` URL is emitted as definition metadata only.
  There is no Java callback handler, controller or runtime route registration in this slice.
- The aggregate deliberately omits the SDK's conventional `caseHistory` field/tab and default `LiveFrom` values where
  the golden rows omit them.
- The cftlib and production definitions for this aggregate are identical, so 156 exact rows remove 312 baseline
  differences.

Post-commit generator-fit review:

- Review point: root `4253eb3e`, ET `5cd2c9b6d`.
- Finding: all 35 case fields repeated `access = PreHearingDepositAccess.class`, obscuring the field labels and types.
- Decision: add class-level `@CCD(access = ...)` as a reusable default with additive field access, explicit replacement
  and empty opt-out semantics.
- Follow-up: root `c343f2c7`, ET `b0bb67f97`.
- Result: all 312 rows remain exact with zero changed or unexpected rows. ET production/generation code fell by ten
  lines; reusable SDK production code grew by fifteen, a net five-line increase which should amortise over later models.
- No further SDK ergonomics refactor was warranted for this slice. Its remaining event, tab and search builder calls
  express real ordering and conditions rather than accidental repetition.

The primary ET files are:

```text
src/ccdMigration/java/.../ccd/migration/config/PreHearingDepositDefinition.java
src/main/java/.../domain/prehearingdeposit/PreHearingDepositData.java
src/main/java/.../domain/prehearingdeposit/PreHearingDepositState.java
src/main/java/.../domain/prehearingdeposit/PreHearingDepositRole.java
src/main/java/.../domain/prehearingdeposit/PreHearingDepositAccess.java
src/main/java/.../domain/admin/types/ImportFile.java
src/main/java/.../domain/admin/types/Document.java
```

### Slice 2: remainder of `ET_Admin`

Status: complete and exact in both `cftlib` and `prod`.

The slice adds the 149 previously missing admin rows in each environment:

| Workbook sheet | Exact rows added per environment |
| --- | ---: |
| `CaseType` | 1 |
| `CaseField` | 19 |
| `CaseTypeTab` | 3 |
| `CaseEvent` | 13 |
| `CaseEventToFields` | 36 |
| `CaseEventToComplexTypes` | 8 |
| `State` | 1 |
| `FixedLists` | 18 |
| `ComplexTypes` | 6 |
| `SearchInputFields` | 1 |
| `SearchResultFields` | 1 |
| `WorkBasketInputFields` | 1 |
| `WorkBasketResultFields` | 1 |
| `AuthorisationCaseType` | 1 |
| `AuthorisationCaseField` | 19 |
| `AuthorisationCaseEvent` | 13 |
| `AuthorisationCaseState` | 1 |
| `AuthorisationComplexType` | 6 |
| **Total** | **149** |

Important implementation choices:

- `AdminData` and its existing nested runtime DTOs remain the wire models; CCD labels and type overrides are metadata
  on those types rather than a parallel definition model.
- The 13 existing external callback paths, including page-level mid-event paths, are emitted as URL metadata only.
  No handler, controller or route registration moved into the SDK.
- The generated fixed-list enums live in the generation-only source set and preserve all 18 external codes and display
  orders without changing the runtime `String` fields.
- The admin and pre-hearing-deposit configurations both emit `EMPLOYMENT` and `ImportFile`. Staging now coalesces
  identical jurisdiction-global rows, preserves duplicates owned by one case type and fails with owner names when two
  case types emit conflicting rows.
- The two legacy text cells containing canonical unsigned integers (`PageID` and `DisplayOrder`) compare semantically
  with numeric generated cells. No leading-zero or non-numeric strings, and no other columns, are normalised.
- All 149 rows are exact in both profiles. The full admin jurisdiction is now 305/305 exact in each profile, with zero
  changed or unexpected rows.

The primary ET files are:

```text
src/ccdMigration/java/.../ccd/migration/config/AdminDefinition.java
src/ccdMigration/java/.../ccd/migration/config/AdminImportEvents.java
src/ccdMigration/java/.../ccd/migration/config/AdminStaffEvents.java
src/ccdMigration/java/.../ccd/migration/config/AdminFileLocationEvents.java
src/main/java/.../domain/admin/AdminData.java
src/main/java/.../domain/admin/AdminState.java
src/main/java/.../domain/admin/AdminRole.java
src/main/java/.../domain/admin/AdminAccess.java
```

Post-commit generator-fit review:

- Review point: root `c58ca56c`, ET `3d551154d`.
- Finding: four events used `endButtonLabel("")` to suppress an optional CCD column. The empty string preserved parity
  but hid the definition intent and would recur in later ET event conversions.
- Decision: add the typed, backwards-compatible `omitEndButtonLabel()` event metadata API and refactor those four
  events. Keep the four configuration modules and their explicit page/event sequences: they represent different
  callbacks, field contexts and golden display metadata rather than accidental duplication.
- Follow-up: root review commit containing this record, ET `bb666f5e5`.
- Result: all 610 rows remain exact, with zero changed or unexpected rows. ET production/generation LOC is unchanged;
  SDK production LOC increases by six and verification LOC by one. No further SDK refactor is warranted for this slice.

### Slice 3: paired regional Listings aggregates

Status: exact in both `cftlib` and `prod`; generator-fit review follows the exact-conversion commits.

The slice owns 451 England/Wales rows and 468 Scotland rows in each environment:

| Workbook sheet | England/Wales | Scotland | Both per environment |
| --- | ---: | ---: | ---: |
| `Jurisdiction` | 1 | 1 | 2 |
| `CaseType` | 1 | 1 | 2 |
| `CaseField` | 30 | 36 | 66 |
| `ComplexTypes` | 187 | 191 | 378 |
| `State` | 2 | 2 | 4 |
| `CaseEvent` | 7 | 7 | 14 |
| `CaseEventToFields` | 21 | 21 | 42 |
| `CaseTypeTab` | 37 | 30 | 67 |
| regional fixed-list sheet | 36 | 30 | 66 |
| `SearchInputFields` | 1 | 2 | 3 |
| `SearchResultFields` | 1 | 2 | 3 |
| `WorkBasketInputFields` | 2 | 2 | 4 |
| `WorkBasketResultFields` | 2 | 2 | 4 |
| `RoleToAccessProfiles` | 5 | 5 | 10 |
| `AuthorisationCaseType` | 5 | 5 | 10 |
| `AuthorisationCaseField` | 90 | 108 | 198 |
| `AuthorisationCaseEvent` | 13 | 13 | 26 |
| `AuthorisationCaseState` | 10 | 10 | 20 |
| **Total** | **451** | **468** | **919** |

Important implementation choices:

- `ListingData` remains the shared runtime wire model. Typed England/Wales and Scotland schema profiles select the
  regional projection without empty subclasses; repeatable `@CCD` metadata handles the few fields whose external ID or
  definition differs by region.
- The seven event IDs and their common structure are shared, while the two case-type foundations retain their exact
  regional metadata, roles, fields, tabs and permissions.
- Reachable complex types resolve through ET's concrete collection item wrappers. The wrappers stay in the runtime
  model and opt into narrow value-type resolution rather than being emitted as spurious CCD complex types.
- The two case types share one region-neutral fixed-list module through multiple grouping keys. Identical global rows
  coalesce under the existing ownership checks; regional office and venue lists remain explicit.
- Applicable-role selection prevents regional roles leaking across case types. Legacy `CaseTypeId` authorisation
  columns, Scotland's three selective authorisation `LiveFrom` values and profile-specific access-profile rows remain
  exact.
- Existing definition quirks are preserved, including the `docMarkUp ` case-field ID with its distinct `docMarkUp`
  authorisation ID and the historical regional report-field IDs. No golden JSON was changed.
- Existing callback URLs are emitted as definition metadata only. No callback handler, controller or route wiring is
  part of the slice.
- The pair contributes 919 exact rows in each profile, removing 1,838 baseline differences. All generated rows are
  exact, with zero changed or unexpected rows.

The primary ET files are:

```text
src/ccdMigration/java/.../ccd/migration/config/EnglandWalesListingDefinition.java
src/ccdMigration/java/.../ccd/migration/config/ScotlandListingDefinition.java
src/ccdMigration/java/.../ccd/migration/config/ListingDefinitionSupport.java
src/ccdMigration/java/.../ccd/migration/config/CommonListingFixedLists.java
et-shared/src/main/java/.../model/listing/ListingData.java
et-shared/src/main/java/.../model/listing/types/*.java
et-shared/src/main/java/.../model/ccd/ListingRole.java
et-shared/src/main/java/.../model/ccd/ListingAccess.java
```

## SDK migration capabilities

Capabilities delivered by Slices 1 to 3 and available for reuse:

- typed `CaseType` and `Jurisdiction` metadata, including live dates, printable-document URLs, shuttering, deletion and
  retry metadata;
- optional omission of generator-default `LiveFrom` values;
- optional omission of the conventional case-history field, tab and authorisation;
- class-level default case-field access with additive, replacement and empty opt-out field semantics;
- external event callback URL metadata which is mutually exclusive with a Java handler;
- external page-level mid-event URL metadata which is mutually exclusive with a Java handler;
- optional omission of `ShowSummary`, `ShowEventNotes`, `Publish`, page-display-order and page-column output;
- explicit wildcard post-state metadata for definitions whose single Java state would otherwise render as that state;
- explicit event-to-complex row IDs and labels without disturbing legacy generator defaults;
- fixed-list registration by external ID, independent of the runtime case-field Java type;
- a configurable conventional case-history label;
- exclusion of non-enum runtime wrapper types when an explicit CCD field type owns their schema;
- jurisdiction-level coalescing and conflict validation for `Jurisdiction`, complex-type, fixed-list and
  event-to-complex rows;
- tabs without the default `CaseWorker` channel;
- `FieldType.DateTime` and `FieldType.AddressUK` where runtime Java fields must retain a different type;
- multiple grouping keys for one region-neutral shared configuration module;
- case-type-specific applicable-role selection across case-role, access-profile and authorisation generation;
- typed regional schema profiles, including repeatable profile-specific field IDs, authorisation IDs and metadata;
- explicit ET collection-wrapper value resolution without generating wrapper complex types;
- sparse tab metadata, explicit tab-field ordering and targeted tab show conditions;
- exact field-level page labels, mid-event URLs and retain-hidden-value metadata; and
- optional legacy authorisation column names and selective retention of access-profile and authorisation `LiveFrom`
  values.

Class-level access is a default for `AuthorisationCaseField` generation only. It does not grant
`AuthorisationComplexType` permissions: complex-type access remains explicit through `builder.grantComplexType(...)`.

These capabilities have backwards-compatible defaults and focused SDK generation, precedence and
invalid-combination tests in `MigrationDefinitionGeneratorTest`, `MigrationArchitectureTest`,
`AuthorisationCaseFieldGeneratorTest` and `AuthorisationCaseTypeGeneratorTest`.

Capabilities anticipated by the architecture but not yet delivered:

- explicit `cftlib`/`prod` module selection for environment-only definitions; and
- the build-side ownership manifest and legacy/Java overlay used for deployable packaging.

Add these only when the next coherent slice needs them. Do not implement the whole list speculatively.

## Recommended next slice: paired regional Multiples aggregates

The recommended fourth slice is `ET_EnglandWales_Multiple` together with `ET_Scotland_Multiple`. They share the
`MultipleData` runtime model and `MultipleCaseState`, and are the next smallest regional aggregate boundary after
Listings. Converting them together continues to exercise the regional profile and applicable-role APIs while avoiding
a one-region implementation which bakes regional differences into shared code.

The processed `cftlib` workbooks contain 1,325 England/Wales and 1,318 Scotland case-type-specific rows for the pair, so
2,643 exact rows is the workbook-derived minimum forecast for that profile alone. The full forecast must add newly
owned complex-type, fixed-list, event-to-complex and complex-authorisation rows, then separately inventory `prod`:
non-production Multiples files mean the two profiles are not safely assumed to have the same total.

Before implementation, inventory those rows exactly and prove:

- how `MultipleData`'s 34 redeclarations of fields inherited from `BaseCaseData` map to one CCD identity each, without
  changing the runtime serialisation model or relying on reflection order;
- which modules are common, regional, production-only or non-production-only;
- which shared complex types and fixed lists are already exact because Listings owns them, and which are newly owned;
- that concrete collection wrappers continue to resolve to their value types and conflicting duplicate field metadata
  fails explicitly; and
- whether the planned environment-module selector is required for this slice or whether the exact prod/cftlib boundary
  can be expressed with a smaller typed API.

This is a recommendation, not permission to skip the normal starting inventory. Re-run convergence and verify the
golden workbooks before choosing or implementing the slice.

## Per-slice resumption and delivery workflow

At the beginning of a slice:

1. inspect both Git worktrees and the submodule pointer;
2. read this handoff and the architecture delivery sequence;
3. run `./gradlew :et:etMigrationProgress` to prove the committed starting point;
4. inventory the exact golden rows owned by the proposed vertical slice; and
5. state the expected exact-row gain and expected SDK capabilities before editing Java.

During implementation:

1. keep runtime callbacks and persisted DTO compatibility out of definition-only changes;
2. add narrow typed SDK features only for real CCD concepts;
3. keep generated output under `build/ccd-migration`;
4. resolve all changed and unexpected rows before accepting exact progress; and
5. monitor ET and SDK production Java growth rather than optimising only the percentage.

To create the exact conversion review point:

1. run the focused SDK tests and ET generation, convergence, Checkstyle and PMD gates;
2. run `updateEtMigrationProgress`, review its diff, then rerun `etMigrationProgress`;
3. update this file's date, current convergence, milestone table, completed-slice ledger, delivered capabilities, next
   slice forecast and known blockers;
4. confirm golden definitions are unchanged;
5. inspect `ccd-definitions/.yarn/install-state.gz` and do not commit it when the only change came from the tooling;
6. commit ET implementation and snapshot changes in the ET repository;
7. commit SDK, prompt and ET submodule-pointer changes in the root repository; and
8. do not amend or squash this working conversion merely because a later review identifies better SDK ergonomics.

After those commits exist, perform the generator-fit review described in the convergence tooling prompt:

1. review the committed Java and its generated JSON/XLSX for repetition, awkward abstractions and unnecessary line
   growth;
2. record the decision in this handoff, including a reason when no new SDK feature is warranted;
3. if a narrow typed SDK improvement is justified, add focused tests and refactor the committed slice;
4. prove the exact/changed/unexpected counts are unchanged and update the LOC snapshot;
5. commit the ET refactor first and the SDK/submodule follow-up second, without rewriting the original conversion
   commits; and
6. when publishing either review point, push the ET branch before the root branch.

The handoff update and post-commit generator-fit review are both part of slice completion. A metric snapshot without this
semantic ledger is not sufficient for a future session to resume safely.

Every completed slice must leave exactly one recommended next coherent slice in this handoff. The recommendation must
state its aggregate boundary, a workbook-derived minimum or exact row forecast, the SDK capabilities it is expected to
exercise and known blockers. The next session must still re-inventory the golden workbooks before accepting it.

If review identifies a potentially benign improvement to the existing CCD definition, preserve the exact conversion
commit and follow the intentional-definition-change process in the style guide. Commit the improvement separately,
change the active legacy JSON and Java owner together, and prove its runtime and security effect with commit-pinned
source references through the applicable CCD backend, `xui-webapp` and `ccd-case-ui-toolkit` layers plus focused
behavioural evidence. Record those references, the canonical row delta and convergence impact here. Permissions on
`Label` fields are candidates for evidence-based review, not an automatic exception to authorisation parity.

## Known repository-wide blockers

As of the review date:

- full ET `check` resolution has an unrelated `org.lz4:lz4-java` versus `at.yawk.lz4` capability conflict in the cftlib
  IDE classpath; focused migration compilation, convergence, Checkstyle and PMD tasks remain required;
- SDK test-source Checkstyle currently reports broad pre-existing violations under the repository's current rules; new
  migration tests must still be focused, pass normally and introduce no new reported violations; and
- the CCD definitions Yarn setup may modify tracked `.yarn/install-state.gz`; inspect it after tooling runs and exclude a
  tool-generated-only change from commits.

Do not hide a new failure under these known issues. Record a blocker here only after proving that it exists independently
of the current slice.

## Scope boundaries which remain in force

- Golden ET JSON remains unchanged in conversion commits. A separate, evidenced definition-improvement commit may
  update both the active legacy definition and its Java owner while retaining exact XLSX parity.
- Java-only convergence and deployable legacy/Java overlay are separate milestones.
- Callback wiring remains out of scope; only exact generated callback URL metadata is included.
- Use composition for case-type and regional sharing. Do not create a `CCDConfig` inheritance hierarchy or empty regional
  data subclasses.
- Existing ET runtime models remain the wire models unless a separate compatibility-tested refactor is authorised.
- A slice moves a coherent feature with its fields, nested types, events, conditions and permissions; it does not move a
  spreadsheet sheet in isolation.
