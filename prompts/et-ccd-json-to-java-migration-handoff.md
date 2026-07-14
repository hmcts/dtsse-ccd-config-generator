# ET CCD JSON-to-Java migration status and handoff

This is the living resumption document for the ET CCD JSON-to-Java migration. Read it before starting another slice and
update it whenever a slice, migration capability, metric or known blocker changes. The architecture plan is the stable
design; this file records the current implementation state and why it looks the way it does.

Last reviewed: 14 July 2026.

## Resume here

The migration spans the generator repository and its ET submodule:

| Repository | Branch | Current migration milestone |
| --- | --- | --- |
| `hmcts/dtsse-ccd-config-generator` | `json-to-java` | `0a80b1ec` — post-commit generator-fit review workflow |
| `hmcts/et-ccd-callbacks` | `json-to-java-migration` | `b0bb67f97` — Slice 1 class-level access follow-up |

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
6. [`../test-projects/et-ccd-callbacks/docs/testing-strategy.md`](../test-projects/et-ccd-callbacks/docs/testing-strategy.md).

## Current convergence

The immutable initial baseline is 52,227 remaining differences across `cftlib` and `prod`. The committed snapshot after
the first slice is:

| Metric | Current value |
| --- | ---: |
| Exact Java rows | 312 |
| Changed rows | 0 |
| Unexpected rows | 0 |
| Remaining differences | 51,915 |
| Completed differences | 312 |
| Completion | 0.60% |
| ET production/generation Java delta | +267 |
| SDK production Java delta | +273 |
| Total production Java delta | +540 |
| Verification Java delta | +330 |
| Production lines per completed difference | 1.73 |

The authoritative values are in
`test-projects/et-ccd-callbacks/ccd-definitions/migration-progress.json`. If this table and the snapshot disagree, the
snapshot wins and this handoff must be corrected in the same change.

Verify the committed state from the root repository with:

```shell
./gradlew :et:etMigrationProgress
```

This generates Java-only JSON and XLSX artefacts. It does not start ET, wire callbacks or alter the golden definition.

## Milestone history

| Milestone | Root commit | ET commit | Result |
| --- | --- | --- | --- |
| Migration guide | `c2e1bb86` | — | Established golden-parity and typed-Java rules |
| Convergence baseline | `f0de33c4` | `af0ed98be` | Established 52,227 differences and Java LOC tracking |
| Architecture | `89c94b4f` | — | Chose eight aggregates, composition and explicit profiles |
| Slice 1: `Pre_Hearing_Deposit` | `4253eb3e` | `5cd2c9b6d` | Added 312 exact rows; reached 0.60% |
| Tooling resumption prompt | `ebc0e30e` | — | Documented how to run and interpret convergence |
| Slice 1 generator-fit follow-up | `c343f2c7` | `b0bb67f97` | Replaced repeated field access with a typed class default |

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

## SDK migration capabilities

Capabilities delivered by Slice 1 and available for reuse:

- typed `CaseType` and `Jurisdiction` metadata, including live dates, printable-document URLs, shuttering, deletion and
  retry metadata;
- optional omission of generator-default `LiveFrom` values;
- optional omission of the conventional case-history field, tab and authorisation;
- class-level default case-field access with additive, replacement and empty opt-out field semantics;
- external event callback URL metadata which is mutually exclusive with a Java handler;
- optional omission of `ShowSummary`, `Publish` and event-field page-column output;
- tabs without the default `CaseWorker` channel; and
- `FieldType.DateTime` where the runtime Java field must remain a string.

Class-level access is a default for `AuthorisationCaseField` generation only. It does not grant
`AuthorisationComplexType` permissions: complex-type access remains explicit through `builder.grantComplexType(...)`.

These capabilities have backwards-compatible defaults and focused SDK generation, precedence and
invalid-combination tests in `MigrationDefinitionGeneratorTest` and `AuthorisationCaseFieldGeneratorTest`.

Capabilities anticipated by the architecture but not yet delivered:

- multiple grouping keys for one region-neutral shared configuration module;
- case-type-specific applicable-role selection;
- typed regional schema profiles for shared union models;
- fixed-list registration independent of runtime field type;
- ET concrete collection-wrapper resolution;
- jurisdiction-level aggregation and conflict validation for global rows; and
- the build-side ownership manifest and legacy/Java overlay used for deployable packaging.

Add these only when the next coherent slice needs them. Do not implement the whole list speculatively.

## Next slice: complete `ET_Admin`

The agreed second slice is the rest of the admin jurisdiction. It proves two case types sharing one jurisdiction and the
`ImportFile` complex type, and introduces the first fixed lists and event-to-complex rows without regional variation.

After Slice 1, each environment has exactly 149 admin differences remaining:

| Workbook sheet | Remaining rows per environment |
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

A complete exact `ET_Admin` slice should therefore add 149 exact rows in each environment, remove 298 more baseline
differences and move the headline metric to approximately 1.17%. Re-inventory the golden rows before implementation;
the committed XLSX comparison, not this forecast, is the acceptance authority.

Do not start by annotating all admin classes. First identify the complete aggregate boundary, its fixed-list codes,
nested event fields, permissions and the six remaining complex-type rows. Reuse `ImportFile` without emitting duplicate
global rows. If global-row coalescing is needed, implement the narrow jurisdiction aggregation capability with conflict
tests before enabling the second owner.

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

- Golden ET JSON remains unchanged throughout convergence.
- Java-only convergence and deployable legacy/Java overlay are separate milestones.
- Callback wiring remains out of scope; only exact generated callback URL metadata is included.
- Use composition for case-type and regional sharing. Do not create a `CCDConfig` inheritance hierarchy or empty regional
  data subclasses.
- Existing ET runtime models remain the wire models unless a separate compatibility-tested refactor is authorised.
- A slice moves a coherent feature with its fields, nested types, events, conditions and permissions; it does not move a
  spreadsheet sheet in isolation.
