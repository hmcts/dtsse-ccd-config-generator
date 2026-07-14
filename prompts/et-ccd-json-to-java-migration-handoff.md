# ET CCD JSON-to-Java migration status and handoff

This is the living resumption document for the ET CCD JSON-to-Java migration. Read it before starting another slice and
update it whenever a slice, migration capability, metric or known blocker changes. The architecture plan is the stable
design; this file records the current implementation state and why it looks the way it does.

Last reviewed: 14 July 2026.

## Resume here

The migration spans the generator repository and its ET submodule:

| Repository | Branch | Current reviewed state |
| --- | --- | --- |
| `hmcts/dtsse-ccd-config-generator` | `json-to-java` | Slice 4 generator-fit follow-up in this commit |
| `hmcts/et-ccd-callbacks` | `json-to-java-migration` | `0ccb196ae` — Slice 4 generator-fit follow-up |

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

The immutable initial baseline is 52,227 remaining differences across `cftlib` and `prod`. The committed post-review
snapshot after the fourth slice is:

| Metric | Current value |
| --- | ---: |
| Exact Java rows | 7,719 |
| Changed rows | 0 |
| Unexpected rows | 0 |
| Remaining differences | 44,508 |
| Completed differences | 7,719 |
| Completion | 14.78% |
| ET production/generation Java delta | +13,828 |
| SDK production Java delta | +1,107 |
| Total production Java delta | +14,935 |
| Verification Java delta | +863 |
| Production lines per completed difference | 1.93 |

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
| Slice 3: paired regional Listings | `c3ecad5f` | `2241a7da6` | Added 1,838 exact rows; reached 4.69% |
| Slice 3 generator-fit follow-up | this review commit | `a72f1bee6` | Made access-profile `LiveFrom` retention row-specific |
| Slice 4: paired regional Multiples | `f943c79f` | `63bb25c58` | Added 5,271 exact rows; reached 14.78% |
| Slice 4 generator-fit follow-up | this review commit | `0ccb196ae` | Added profile families and removed repeated profile, access and fixed-list boilerplate |

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

Status: complete and exact in both `cftlib` and `prod`.

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

Post-commit generator-fit review:

- Review point: root `c3ecad5f`, ET `2241a7da6`.
- Finding: `CaseRoleToAccessProfile.retainLiveFrom()` promised row-level retention, but generation retained the column
  only when every access-profile row opted in. Listings uses retention on all five rows, so the exact output masked the
  mixed-row behaviour.
- Decision: centralise the emitted access-profile role name and match retention by that row identity. Add a focused
  mixed-profile test which proves one row retains `LiveFrom` while another omits it.
- Follow-up: root review commit containing this record, ET snapshot commit `a72f1bee6`.
- Result: all 2,448 rows remain exact with zero changed or unexpected rows. The refactor removes one SDK production
  line and adds 33 verification lines relative to the exact-conversion snapshot. The remaining explicit regional
  branches, event sequences and tab rows express real golden-definition differences; no further SDK refactor is
  warranted for this slice.

### Slice 4: paired regional Multiples aggregates

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice adds 5,271 exact rows. Its 3,754 case-type-specific rows pull in 1,517 newly owned jurisdiction-global rows;
the exact dependency closure is therefore larger than the pre-implementation 4,971-row forecast.

| Workbook sheet | cftlib E/W | cftlib Scotland | prod E/W | prod Scotland |
| --- | ---: | ---: | ---: | ---: |
| `CaseType` | 1 | 1 | 1 | 1 |
| `State` | 5 | 5 | 5 | 5 |
| `CaseTypeTab` | 32 | 32 | 14 | 14 |
| `CaseEvent` | 27 | 26 | 16 | 16 |
| `CaseEventToFields` | 148 | 154 | 67 | 73 |
| `CaseField` | 147 | 150 | 56 | 59 |
| `Categories` | 82 | 82 | 82 | 82 |
| `ComplexTypes` | 180 | 180 | 174 | 174 |
| `EventToComplexTypes` | 53 | 52 | 9 | 9 |
| `RoleToAccessProfiles` | 7 | 7 | 5 | 5 |
| regional fixed-list sheet | 272 | 277 | 80 | 85 |
| `SearchInputFields` | 3 | 2 | 3 | 2 |
| `SearchResultFields` | 2 | 2 | 2 | 2 |
| `WorkBasketInputFields` | 3 | 2 | 3 | 2 |
| `WorkBasketResultFields` | 3 | 2 | 3 | 2 |
| `CaseRoles` | 2 | 2 | 2 | 2 |
| `AuthorisationCaseType` | 6 | 6 | 5 | 5 |
| `AuthorisationCaseField` | 732 | 723 | 216 | 227 |
| `AuthorisationCaseEvent` | 89 | 86 | 40 | 36 |
| `AuthorisationCaseState` | 26 | 26 | 25 | 25 |
| **Total** | **1,820** | **1,817** | **808** | **826** |

Important implementation choices:

- `MultipleData` remains the runtime wire model. The generator deterministically selects the most-derived declaration
  for each external CCD field identity, rejects incompatible redeclarations and applies profile exclusion only after
  duplicate resolution. The 34 fields redeclared from `BaseCaseData` therefore generate once without relying on JVM
  reflection order.
- Four typed schema markers represent England/Wales and Scotland in `cftlib` and `prod`. Generation runs with each
  Spring profile into a separate directory, and the convergence tool selects the matching directory while retaining a
  backwards-compatible flat-directory fallback.
- Common roles, access policies, complex-type registration and configuration support are shared. Regional and
  environment differences remain explicit in four small foundation components and profile-specific field metadata.
- The previously converted `ImportFile` complex type remains owned by Admin. Identical global rows continue to coalesce
  at the jurisdiction boundary; Multiples does not create a second owner or change the shared definition.
- Fixed-list Java enums preserve legacy codes and non-sequential display orders independently of Java constant names.
  Definition-only complex types can be registered even when a runtime field names the CCD type explicitly.
- Exact legacy omission and spelling differences are represented through typed metadata, including raw dynamic
  post-state expressions, row-specific tab metadata, explicit `Y`/`N` event-field values, selective authorisation
  inference, case-role live dates and `RetainHiddenValue: Yes`.
- Existing callback URLs are emitted as metadata only. No callback handler, controller or runtime route is introduced.
- Golden JSON is unchanged. All 7,719 generated rows are exact with zero changed or unexpected rows.

The primary ET files are:

```text
src/ccdMigration/java/.../ccd/migration/config/*Multiple*Definition.java
src/ccdMigration/java/.../ccd/migration/config/MultipleDefinitionSupport.java
src/ccdMigration/java/.../ccd/migration/config/MultipleDefinitionRows.java
src/ccdMigration/java/.../ccd/migration/config/MultipleFixedLists.java
et-shared/src/main/java/.../model/multiples/MultipleData.java
et-shared/src/main/java/.../model/generic/BaseCaseData.java
et-shared/src/main/java/.../model/ccd/Multiple*.java
src/main/java/.../domain/caseview/state/MultipleCaseState.java
```

Post-commit generator-fit review:

- Review point: root `f943c79f`, ET `63bb25c58`.
- Finding: the exact conversion repeated four concrete schema profiles across common fields and complex types, expanded
  the same four permission sets in 316 grants, and repeated identical enum constructors and accessors in 47 fixed
  lists. Those forms obscured the regional and environment distinctions which actually matter.
- Decision: allow a schema profile to match an included or excluded parent interface, add typed all-Multiples,
  region and cftlib profile families, name the four ET permission sets, and use Lombok for the fixed-list enum value
  plumbing. The 5,678-line definition-row catalog remains explicit because its ordering, conditions and metadata encode
  real workbook row differences; replacing it with an untyped data loader would weaken the Java ownership boundary.
- Follow-up: root review commit containing this record and SDK change, ET `0ccb196ae`.
- Result: all 7,719 rows remain exact with zero changed or unexpected rows. ET production/generation growth fell from
  16,347 to 13,828 lines while reusable SDK production growth rose from 1,105 to 1,107 lines, reducing total production
  growth by 2,517 lines to 14,935 and improving production lines per completed difference from 2.26 to 1.93.
  Verification growth rose by 20 lines for the profile-family behaviour test.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

## SDK migration capabilities

Capabilities delivered by Slices 1 to 4 and available for reuse:

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
- hierarchical schema-profile families for common, regional and environment-specific field metadata;
- explicit ET collection-wrapper value resolution without generating wrapper complex types;
- sparse tab metadata, explicit tab-field ordering and targeted tab show conditions;
- exact field-level page labels, mid-event URLs and retain-hidden-value metadata; and
- optional legacy authorisation column names and selective retention of access-profile and authorisation `LiveFrom`
  values.
- separate `cftlib` and `prod` generation directories selected by active definition modules and matched by the XLSX
  harness;
- deterministic most-derived field redeclaration with conflicting Java type or applicable `@CCD` metadata rejection;
- explicit complex-type registration and external CCD complex-type names without changing runtime Java field types;
- fixed-list codes independent of Java enum names and row-specific fixed-list display orders;
- raw dynamic event post-state expressions, optional event display-order output and selective event/state/field
  authorisation inference;
- per-row case-role live dates, case-type authorisation exclusions and exact searchable/retain-hidden-value spellings;
  and
- row-specific event-field publish, summary, page-condition and page-column metadata plus row-specific tab label/order
  metadata.

Class-level access is a default for `AuthorisationCaseField` generation only. It does not grant
`AuthorisationComplexType` permissions: complex-type access remains explicit through `builder.grantComplexType(...)`.

These capabilities have backwards-compatible defaults and focused SDK generation, precedence and
invalid-combination tests in `MigrationDefinitionGeneratorTest`, `MigrationArchitectureTest`,
`AuthorisationCaseFieldGeneratorTest` and `AuthorisationCaseTypeGeneratorTest`.

The remaining architecture capability not yet delivered is the build-side ownership manifest and legacy/Java overlay
used for deployable packaging.

Add it only when a coherent slice needs deployable packaging; do not implement it speculatively.

## Recommended next slice: paired Singles schema, base lifecycle and access

The recommended fifth slice prepares and enables `ET_EnglandWales` and `ET_Scotland` together, covering the complete
regional root-field projection and access foundation plus these 14 base lifecycle events:
`initiateCase`, `INITIATE_CASE_DRAFT`, `UPDATE_CASE_DRAFT`, `SUBMIT_CASE_DRAFT`, `UPDATE_CASE_SUBMITTED`,
`REMOVE_OWN_REP_AS_RESPONDENT`, `REMOVE_OWN_REP_AS_CLAIMANT`, `assignCase`, `preAcceptanceCase`,
`acceptRejectedCase`, `disposeCase`, `reinstateClosedCase`, `deleteDraftCase` and `DELETE_DRAFT_CASE`.

This boundary follows the architecture constraint that a Singles foundation must not expose roughly one thousand
unreviewed fields merely to migrate one event. It establishes the full typed schema and explicit field access once, then
moves the first coherent lifecycle family with its pages and event permissions.

The workbook-derived case-type-specific minimum is 24,119 rows before newly owned complex types, fixed lists and
event-to-complex rows:

| Profile and case type | Schema/access foundation | Lifecycle event rows | Minimum |
| --- | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 6,103 | 163 | 6,266 |
| cftlib `ET_Scotland` | 5,730 | 167 | 5,897 |
| prod `ET_EnglandWales` | 6,002 | 161 | 6,163 |
| prod `ET_Scotland` | 5,628 | 165 | 5,793 |
| **Total** | **23,463** | **656** | **24,119** |

The foundation subtotal covers `CaseType`, all regional `CaseField` rows, `CaseRoles`, `State`,
`RoleToAccessProfiles`, and case-type, field and state authorisation. The lifecycle subtotal covers `CaseEvent`,
`CaseEventToFields` and event authorisation for the 14 named IDs. The next session must compute the global dependency
closure and subtract rows already exact through Listings or Multiples before accepting a final forecast.

Expected SDK reuse is typed regional/environment profiles, deterministic inherited-field selection, explicit complex
registration, applicable roles, fixed-list registration and explicit-only authorisation. Known blockers are the
1,930-line `CaseData` union model and its inherited fields, 18,801 field-authorisation rows in the minimum, cftlib/prod
field deltas, and the need to prevent unrelated tab, search and later-event rows from being inferred when the foundations
are enabled. Keep ET1/ET3 intake and every later business family out of this slice.

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
