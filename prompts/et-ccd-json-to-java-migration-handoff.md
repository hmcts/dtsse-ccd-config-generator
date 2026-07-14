# ET CCD JSON-to-Java migration status and handoff

This is the living resumption document for the ET CCD JSON-to-Java migration. Read it before starting another slice and
update it whenever a slice, migration capability, metric or known blocker changes. The architecture plan is the stable
design; this file records the current implementation state and why it looks the way it does.

Last reviewed: 14 July 2026.

## Resume here

The migration spans the generator repository and its ET submodule:

| Repository | Branch | Current reviewed state |
| --- | --- | --- |
| `hmcts/dtsse-ccd-config-generator` | `json-to-java` | Slice 12 generator-fit review in this commit |
| `hmcts/et-ccd-callbacks` | `json-to-java-migration` | `9f0ba610b` — Slice 12 exact conversion; no follow-up refactor required |

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

The immutable initial baseline is 52,227 remaining differences across `cftlib` and `prod`. The exact snapshot after
the twelfth slice is:

| Metric | Current value |
| --- | ---: |
| Exact Java rows | 44,106 |
| Changed rows | 0 |
| Unexpected rows | 0 |
| Remaining differences | 8,121 |
| Completed differences | 44,106 |
| Completion | 84.45% |
| ET production/generation Java delta | +67,352 |
| SDK production Java delta | +1,364 |
| Total production Java delta | +68,716 |
| Verification Java delta | +1,093 |
| Production lines per completed difference | 1.56 |

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
| Slice 5: paired Singles foundation and lifecycle | `d78e66f2` | `f1f439330` | Added 32,840 exact rows; reached 77.66% |
| Slice 5 generator-fit follow-up | this review commit | `13ebb6d1b` | Removed repeated fixed-list value plumbing while preserving exact parity |
| Slice 6: paired Singles ET1 claim intake | `5e295bd2` | `567f83d62` | Added 1,052 exact rows; reached 79.67% |
| Slice 6 generator-fit follow-up | this review commit | `624fa14f7` | Replaced repeated ET1 row defaults with feature-specific factories |
| Slice 7: paired Singles core ET3 response intake | `07566b9b` | `a728b7463` | Added 626 exact rows; reached 80.87% |
| Slice 7 generator-fit follow-up | this review commit | `2d97f2837` | Named ET3 event shapes and respondent-solicitor grant family |
| Slice 8: paired Singles ET3 processing and notification | `41c09858` | `6dfe1014f` | Added 420 exact rows; reached 81.68% |
| Slice 8 generator-fit follow-up | this review commit | `901ddfc6c` | Named shared tribunal grants and the two ET3 event shapes |
| Slice 9: paired Singles core hearing management | `1c757feb` | `b09dfdec7` | Added 598 exact rows; reached 82.82% |
| Slice 9 generator-fit follow-up | this review commit | `4dfbcb703` | Replaced repeated hearing-event, grant and row defaults with feature-local factories |
| Slice 10: paired Singles hearing documents and bundles | `72dcd6ba` | `11bca5627` | Added 240 exact rows; reached 83.28% |
| Slice 10 generator-fit review | this review commit | `11bca5627` | Confirmed the feature-local factories and standalone nested-event API already fit the generator |
| Slice 11: paired Singles correspondence and document serving | `05fcf302` | `efeb69b3c` | Added 321 exact rows; reached 83.90% |
| Slice 11 generator-fit review | this review commit | `efeb69b3c` | Confirmed the feature-local event, page, document-element and grant factories already fit the generator |
| Slice 12: paired Singles tribunal notifications | `a1fa898b` | `9f0ba610b` | Added 290 exact rows; reached 84.45% |
| Slice 12 generator-fit review | this review commit | `9f0ba610b` | Confirmed the feature-local event, field, nested-document and grant factories already fit the generator |

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

### Slice 12: paired Singles tribunal notifications

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the paired tribunal-facing notification workflow: `sendNotification`, `respondNotification`,
`viewAllNotifications` and `generateNotificationSummary`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 4 | 36 | 29 | 2 | 0 | 71 |
| cftlib `ET_Scotland` | 4 | 36 | 30 | 2 | 0 | 72 |
| prod `ET_EnglandWales` | 4 | 36 | 29 | 4 | 0 | 73 |
| prod `ET_Scotland` | 4 | 36 | 30 | 4 | 0 | 74 |
| **Total** | **16** | **144** | **118** | **12** | **0** | **290** |

Important implementation choices:

- All four events move with their page fields, nested event elements and event permissions. The cftlib and production
  callback bases, wildcard states, publish metadata, enabling conditions and the regional judgment/judgement spelling
  remain explicit.
- `sendNotification` retains its Scotland-only page-level validation callback. `respondNotification` retains the
  environment-specific validation callback in both regions, while every notification field preserves its exact
  condition, retain-hidden, summary and publish metadata. All 144 rows intentionally omit `PageColumnNumber`.
- The two `respondNotificationUploadDocument` nested rows exist in every profile. The two
  `sendNotificationUploadDocument` rows remain production-only and retain their different context and label metadata.
- Event permissions retain the Scotland-only base caseworker grant for `sendNotification`, the broad respondent
  solicitor family for `viewAllNotifications`, and the exact regional judge/caseworker CRUD variants. No direct
  `AuthorisationComplexType` rows belong to this slice.
- Callback URLs are generated as metadata only. No callback handler, controller or runtime route registration is
  included.
- The shared `ImportFile` complex type remains owned by the completed Admin conversion and continues to coalesce
  unchanged; this slice adds no competing global complex-type owner.
- Golden JSON is unchanged. All 44,106 generated rows are exact with zero changed or unexpected rows.
- Relative to the reviewed Slice 11 snapshot, ET and total production growth are 776 lines. SDK production and all
  verification growth are unchanged. The cumulative production-lines ratio moves from 1.55 to 1.56.

Post-commit generator-fit review:

- Review point: root `a1fa898b`, ET `9f0ba610b`.
- Finding: the committed 769-line feature catalog already names the shared notification, view and summary event shapes,
  the send/respond field families, the nested-document rows and the four permission families. Its remaining long field
  conditions, retain-hidden values, regional spelling, profile-specific callbacks and role grants are distinct golden
  metadata rather than missing generator concepts.
- Decision: no further ET or SDK refactor is warranted. The reusable SDK already expresses page-column omission,
  external callback metadata, exact event-field metadata, profile masks and multiset-preserving nested rows. A broader
  row DSL or role expansion API would obscure the meaningful notification workflow and permission differences.
- Follow-up: this root review commit records the decision; ET remains at the exact conversion commit `9f0ba610b`.
- Result: all 44,106 rows remain exact with zero changed or unexpected rows. ET production/generation growth remains
  67,352 lines, SDK production growth 1,364 lines, total production growth 68,716 lines and verification growth 1,093
  lines. The cumulative production-lines ratio remains 1.56.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 11: paired Singles correspondence and document serving

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the paired document entry, letter generation and ET1 serving workflow: `addDocument`,
`uploadDocument`, `generateCorrespondence` and `uploadDocumentForServing`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 4 | 23 | 21 | 25 | 0 | 73 |
| cftlib `ET_Scotland` | 4 | 23 | 21 | 24 | 0 | 72 |
| prod `ET_EnglandWales` | 4 | 23 | 21 | 41 | 0 | 89 |
| prod `ET_Scotland` | 4 | 23 | 21 | 39 | 0 | 87 |
| **Total** | **16** | **92** | **84** | **129** | **0** | **321** |

Important implementation choices:

- All four events move with their page fields, nested event elements and event permissions. Their exact pre-state
  ordering, enabling conditions, publish values and cftlib/production callback bases remain explicit.
- England/Wales uses `correspondenceType` and Scotland uses `correspondenceScotType`; the associated top-level and
  part-level page conditions retain their different field paths. Scotland also retains the page label which is absent
  from the England/Wales `sendDocByFirstClass` row.
- `addDocument` retains the regional `Documents` element sets in every profile. England/Wales includes the employer
  contract claim category while Scotland omits it and shifts the following display orders. The legacy equal display
  orders for document index/exclude-from-DCF and date/exclude-from-DCF are preserved exactly.
- `uploadDocument` has no nested rows in cftlib and retains the production-only regional `Documents` element sets.
  Address-label, serving-document and brought-forward-action nested rows retain their exact IDs, contexts, labels and
  publish metadata.
- ET1 serving retains the work-allocation task-configuration grant in addition to the common and regional tribunal
  grant family. No direct `AuthorisationComplexType` rows belong to this slice.
- Callback URLs are generated as metadata only. No callback handler, controller or runtime route registration is
  included.
- Golden JSON is unchanged. All 43,816 generated rows are exact with zero changed or unexpected rows.
- Relative to the reviewed Slice 10 snapshot, ET and total production growth are 884 lines. SDK production and all
  verification growth are unchanged. The cumulative production-lines ratio moves from 1.54 to 1.55.

Post-commit generator-fit review:

- Review point: root `05fcf302`, ET `efeb69b3c`.
- Finding: the committed 877-line feature catalog already names the four environment-specific event shapes, regional
  correspondence page family, common tribunal grants and regional document-element expansion. Its remaining serving
  field specifications carry distinct field/page conditions, labels, positions, summary values or publish metadata;
  the generated cftlib/production difference is expressed by the document-element profile masks rather than duplicated
  event configuration.
- Decision: no further ET or SDK refactor is warranted. A broader field-row abstraction would obscure the event's
  meaningful page flow, while the reusable SDK already models every CCD concept this slice needs.
- Follow-up: this root review commit records the decision; ET remains at the exact conversion commit `efeb69b3c`.
- Result: all 43,816 rows remain exact with zero changed or unexpected rows. ET production/generation growth remains
  66,576 lines, SDK production growth 1,364 lines, total production growth 67,940 lines and verification growth 1,093
  lines. The cumulative production-lines ratio remains 1.55.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 10: paired Singles hearing documents and bundles

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the paired hearing-document and bundle workflow: `bundlesRespondentPrepareDoc`,
`SUBMIT_CLAIMANT_BUNDLES`, `removeHearingBundles`, `uploadHearingDocuments`, `createDcf` and
`asyncStitchingComplete`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 6 | 22 | 28 | 2 | 0 | 58 |
| cftlib `ET_Scotland` | 6 | 22 | 29 | 2 | 0 | 59 |
| prod `ET_EnglandWales` | 6 | 22 | 28 | 5 | 0 | 61 |
| prod `ET_Scotland` | 6 | 22 | 29 | 5 | 0 | 62 |
| **Total** | **24** | **88** | **114** | **14** | **0** | **240** |

Important implementation choices:

- All six events move with their page fields, nested elements and event permissions. Environment callback bases,
  England/Wales and Scotland role families, the Scotland-only API grant for claimant bundle submission, exact
  whitespace and description capitalisation remain explicit.
- Production has three `createDcf` nested rows rooted at `caseBundles` without a corresponding
  `CaseEventToFields` row. The SDK now supports this legacy shape through
  `complexWithoutEventField(...)`, with focused positive and invalid-flattened-property tests.
- The ordinary nested rows retain the exact `uploadedDocument` paths and hidden-value metadata. The production DCF
  rows retain their multilevel `documents`, `documents.name` and `documents.sourceDocument` paths.
- Callback URLs are generated as metadata only. No callback handler, controller or runtime route registration is
  included.
- Golden JSON is unchanged. All 43,495 generated rows are exact with zero changed or unexpected rows.
- Relative to the reviewed Slice 9 snapshot, ET production/generation growth is 793 lines, SDK production growth is
  18 lines and total production growth is 811 lines. SDK verification growth is 43 lines; ET verification growth is
  unchanged. The cumulative production-lines ratio moves from 1.53 to 1.54.

Post-commit generator-fit review:

- Review point: root `72dcd6ba`, ET `11bca5627`.
- Finding: the committed 714-line feature catalog already names the six event shapes, regional tribunal grant family,
  event-field defaults and nested-element defaults. Its remaining 25 event-field and five nested-element specifications
  carry genuine conditions, labels, ordering, profile masks or hidden-value distinctions. The SDK's standalone
  event-to-complex method models one precise legacy omission, rejects flattened properties and does not alter the
  established `complex(...)` behaviour.
- Decision: no further ET or SDK refactor is warranted. A broader abstraction would either duplicate the existing
  feature-local factories or obscure the workbook distinctions the Java definition is intended to own.
- Follow-up: this root review commit records the decision; ET remains at the exact conversion commit `11bca5627`.
- Result: all 43,495 rows remain exact with zero changed or unexpected rows. ET production/generation growth remains
  65,692 lines, SDK production growth 1,364 lines, total production growth 67,056 lines and verification growth 1,093
  lines. The cumulative production-lines ratio remains 1.54.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 9: paired Singles core hearing management

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the four core tribunal hearing-management events: `allocateHearing`, `printHearing`,
`addAmendHearing` and `updateHearing`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 4 | 22 | 21 | 62 | 39 | 148 |
| cftlib `ET_Scotland` | 4 | 24 | 21 | 70 | 32 | 151 |
| prod `ET_EnglandWales` | 4 | 22 | 21 | 62 | 39 | 148 |
| prod `ET_Scotland` | 4 | 24 | 21 | 70 | 32 | 151 |
| **Total** | **16** | **92** | **84** | **264** | **142** | **598** |

Important implementation choices:

- The four events move with all page fields, nested hearing elements and event permissions. Their shared dynamic
  `Accepted`/`Rejected` post-state expression, enabling condition, display metadata and environment-specific external
  callback URLs remain exact.
- The 142 directly joined `AuthorisationComplexType` rows use typed grants on
  `CaseData::getHearingCollection` and `CaseData::getHearingDetailsCollection`. England/Wales retains read-only
  legal-representative access to hearing elements, Scotland retains CRU, and employment caseworkers retain CRU in both
  regions. England/Wales hearing-detail elements retain their separate read-only and delete-only legal-representative
  sets.
- England/Wales and Scotland keep their different event fields, nested hearing elements and element permissions.
  Identical cftlib and production rows are shared through profile masks; callback bases remain profile-specific.
- Callback URLs are generated as metadata only. No callback handler, controller or runtime route registration is
  included.
- Golden JSON is unchanged. All 43,255 generated rows are exact with zero changed or unexpected rows.

Post-commit generator-fit review:

- Review point: root `1c757feb`, ET `b09dfdec7`.
- Finding: all 43 event-field specifications use the same page-column convention, all 105 nested-element
  specifications omit the same four optional metadata values, and the same seven regional tribunal grant policies are
  repeated across the four events. Each event is also expanded twice solely to substitute its cftlib or production
  callback base.
- Decision: use feature-local factories for the hearing-event shapes, tribunal grants, event fields and nested elements.
  These defaults are specific to this coherent hearing workflow, so no SDK change is warranted; the exceptional row
  metadata and direct complex-type permissions remain explicit.
- Follow-up: root review commit containing this record and submodule update, ET `4dfbcb703`.
- Result: all 43,255 rows remain exact with zero changed or unexpected rows. The feature catalog fell from 2,532 to
  1,865 lines. Relative to the Slice 8 reviewed snapshot, ET and total production growth are 1,873 lines; SDK production
  and all verification growth are unchanged. The cumulative production-lines ratio moves from 1.51 to 1.53.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 8: paired Singles ET3 processing and notification

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts tribunal processing of a submitted ET3 and the associated notification flow: `et3Vetting` and
`et3Notification`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 2 | 81 | 11 | 6 | 4 | 104 |
| cftlib `ET_Scotland` | 2 | 81 | 13 | 6 | 4 | 106 |
| prod `ET_EnglandWales` | 2 | 81 | 11 | 6 | 4 | 104 |
| prod `ET_Scotland` | 2 | 81 | 13 | 6 | 4 | 106 |
| **Total** | **8** | **324** | **48** | **24** | **16** | **420** |

Important implementation choices:

- The two events, their 81 page-field rows per variant and their six nested event-element rows are kept together as one
  vertical feature. Profile masks share identical rows while retaining the two extra Scotland event grants.
- The four directly joined `AuthorisationComplexType` rows per variant are explicit typed grants on
  `CaseData::getRespondentCollection`. The three dotted or event-level element identities retain delete-only legal-
  representative access, and the event-level element also retains delete-only employment-caseworker access.
- The nested `respondentCollection` rows retain their separate publish metadata. This does not broaden their explicit
  complex-type permissions.
- External localhost and production callback URLs are generated as metadata only. No callback handler, controller or
  runtime route registration is included.
- Golden JSON is unchanged. All 42,657 generated rows are exact with zero changed or unexpected rows.

Post-commit generator-fit review:

- Review point: root `41c09858`, ET `6dfe1014f`.
- Finding: the exact catalog repeated the same eight regional tribunal grant policies across both events and expanded
  each event twice solely to substitute the cftlib or production callback base. The 94 event-field specifications and
  six nested-element specifications remain meaningfully distinct through their contexts, conditions, retention,
  publish, labels and regional rows.
- Decision: name the shared tribunal grant family and the processing and notification event shapes while leaving the
  row-specific field and nested-element metadata explicit. These are feature-local ET3 policies, so no SDK change is
  warranted.
- Follow-up: root review commit containing this record and submodule update, ET `901ddfc6c`.
- Result: all 42,657 rows remain exact with zero changed or unexpected rows. The feature catalog fell from 1,642 to
  1,619 lines. Relative to the Slice 7 reviewed snapshot, ET and total production growth are 1,627 lines; SDK production
  and all verification growth are unchanged. The cumulative production-lines ratio remains 1.51.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 7: paired Singles core ET3 response intake

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the respondent-facing ET3 authoring, draft-download and submission flow:
`et3Response`, `et3ResponseEmploymentDetails`, `et3ResponseDetails`, `downloadDraftEt3` and `submitEt3`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 5 | 80 | 69 | 1 | 155 |
| cftlib `ET_Scotland` | 5 | 83 | 69 | 1 | 158 |
| prod `ET_EnglandWales` | 5 | 80 | 69 | 1 | 155 |
| prod `ET_Scotland` | 5 | 83 | 69 | 1 | 158 |
| **Total** | **20** | **326** | **276** | **4** | **626** |

Important implementation choices:

- The complete Singles schema remains owned by Slice 5. This feature adds only its five events, page fields, one nested
  response element per variant and event permissions; the direct workbook identity join finds no additional
  `AuthorisationComplexType` ownership.
- All ten respondent solicitor case roles, claimant-solicitor delete access, ACAS read access, API and work-allocation
  grants are retained exactly. Regional employment and judge grants remain explicit on `submitEt3`.
- England/Wales and Scotland keep their distinct event display order and Scotland's `submitEt3` enabling condition.
  Retain-hidden, publish, page-column, summary-change and callback metadata remain row-specific.
- External localhost and production callback URLs are generated as metadata only. No handler, controller or runtime
  route registration is included.
- Golden JSON is unchanged. All 42,237 generated rows are exact with zero changed or unexpected rows.

Post-commit generator-fit review:

- Review point: root `07566b9b`, ET `a728b7463`.
- Finding: the 1,488-line ET3 catalog expanded the same ten respondent-solicitor grants across five events and repeated
  20 event records which follow three stable ET3 section, draft-download and submission shapes. The 88 event-field
  specifications remain meaningfully distinct through their contexts, conditions, retention, labels and regional rows.
- Decision: name the respondent-solicitor permission family and introduce feature-local ET3 event factories which keep
  profile mask, display order, publish flag and Scotland's enabling condition explicit. No SDK feature is warranted:
  these are ET3 business families rather than missing reusable CCD concepts.
- Follow-up: root review commit containing this record and submodule update, ET `2d97f2837`.
- Result: all 42,237 rows remain exact with zero changed or unexpected rows. The ET3 catalog fell from 1,488 to 1,242
  lines. Relative to the Slice 6 reviewed snapshot, ET and total production growth are 1,249 lines; SDK production and
  all verification growth are unchanged. The cumulative production-lines ratio moves from 1.48 to 1.49.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 6: paired Singles ET1 claim intake

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice converts the ET1 claim creation, drafting, document-generation, submission and vetting family:
`et1SectionOne`, `et1SectionTwo`, `et1SectionThree`, `createDraftEt1`, `generateEt1Documents`,
`et1ReppedCreateCase`, `submitEt1Draft` and `et1Vetting`.

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Exact gain |
| --- | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 8 | 214 | 19 | 24 | 265 |
| cftlib `ET_Scotland` | 8 | 210 | 19 | 24 | 261 |
| prod `ET_EnglandWales` | 8 | 214 | 19 | 24 | 265 |
| prod `ET_Scotland` | 8 | 210 | 19 | 24 | 261 |
| **Total** | **32** | **848** | **76** | **96** | **1,052** |

Important implementation choices:

- The complete Singles case-field and complex-type schema remains owned by Slice 5. This feature adds only its event,
  page, nested event-field and event-authorisation rows; a direct workbook identity join finds no additional
  `AuthorisationComplexType` ownership.
- Exact event end-button labels, significant-event flags, string-valued TTL increments and environment-specific
  external callback URLs are retained. Callback handlers, controllers and runtime route registration remain out of
  scope.
- The golden `et1Vetting` pre-state sequence is `Submitted;Rejected;Vetted`. A typed SDK pre-state-order override now
  preserves that canonical order while validating that it contains exactly the configured states; the default sorted
  behaviour remains unchanged.
- Regional differences in claimant hearing/contact fields and the repped triage error page remain explicit through the
  existing typed Singles profile masks. Identical event rows and grants are shared across both regions and profiles.
- Golden JSON is unchanged. All 41,611 generated rows are exact with zero changed or unexpected rows.

Post-commit generator-fit review:

- Review point: root `5e295bd2`, ET `567f83d62`.
- Finding: the 4,498-line ET1 catalog repeated page-order values, null retention/publish metadata, summary defaults and
  long callback URL bases across 238 event-field specifications. Those values follow four stable ET1-local row shapes;
  the event ownership, regional/profile masks, page conditions and exceptional metadata remain meaningful and explicit.
- Decision: introduce feature-specific `readOnly`, `mandatory`, `optional`, `complex` and nested-complex factories plus
  local event, profile and callback-base constants. No further SDK feature is warranted because this repetition is local
  to the ET1 catalog rather than a missing CCD concept.
- Follow-up: root review commit containing this record and submodule update, ET `624fa14f7`.
- Result: all 41,611 rows remain exact with zero changed or unexpected rows. The ET1 catalog fell from 4,498 to 2,702
  lines. Relative to the Slice 5 reviewed snapshot, ET production/generation growth is 2,768 lines, reusable SDK
  production growth is 27 lines and SDK verification growth is 34 lines. Total production growth is 2,795 lines and
  the cumulative production-lines ratio moves from 1.45 to 1.48.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

### Slice 5: paired regional Singles foundation and base lifecycle

Status: complete and exact in `cftlib` and `prod`, including the post-commit generator-fit review.

The slice adds 32,840 exact rows by enabling the complete `ET_EnglandWales` and `ET_Scotland` root schema, roles,
access foundation and the 14 base lifecycle events named in the previous recommendation. The committed closure is:

| Profile and case type | Exact-row gain |
| --- | ---: |
| cftlib `ET_EnglandWales` | 8,403 |
| cftlib `ET_Scotland` | 7,967 |
| prod `ET_EnglandWales` | 8,453 |
| prod `ET_Scotland` | 8,017 |
| **Total** | **32,840** |

Important implementation choices:

- `CaseData`, `Et1CaseData` and `BaseCaseData` remain the runtime wire models. Repeatable profile metadata projects the
  complete Singles workbook schema while profile-scoped ignores prevent 77 unrelated runtime-only fields and their
  reachable Java types from becoming unexpected CCD rows.
- Four concrete generation modules share typed Singles, regional and environment profile families. Roles, 220 distinct
  access policies, 86 explicitly registered complex types and 204 fixed lists are defined once and selected by profile.
- The 14 base lifecycle events include their exact pages, conditions, event permissions, nested event rows and callback
  URL metadata. No callback handler, controller or runtime route is introduced.
- Definition-only field metadata retains legacy numeric fixed-list cells, string-valued bounds, searchable and security
  spellings, omitted display orders and authorisation-only fields without changing runtime DTO types.
- The previously converted `ImportFile` complex type remains a shared global owner. Identical rows coalesce across case
  types, while duplicate fixed-list and event-to-complex occurrences within one definition remain a multiset.
- Golden JSON is unchanged. All 40,559 generated rows are exact with zero changed or unexpected rows.

Post-commit generator-fit review:

- Review point: root `d78e66f2`, ET `f1f439330`.
- Finding: the 248 fixed-list enums repeated hand-written constructors and `HasCode`/`HasLabel` accessors. This was
  accidental value plumbing rather than CCD metadata, and the preceding Multiples slice already established Lombok for
  the identical enum shape. The large case-field, complex-type, access-policy and definition-row catalogs remain
  explicit because their types, regional selection, ordering, conditions and legacy metadata encode real workbook
  differences.
- Decision: align the Singles fixed lists with the proven `@Getter` and `@RequiredArgsConstructor` representation. No
  SDK feature is warranted: the generator already accepts the typed enum values, and adding a data-loading abstraction
  would weaken the Java ownership boundary without removing meaningful CCD complexity.
- Follow-up: root review commit containing this record and submodule update, ET `13ebb6d1b`.
- Result: all 40,559 rows remain exact with zero changed or unexpected rows. ET production/generation growth fell by
  3,222 lines from 60,604 to 57,382; total production growth fell from 61,923 to 58,701, improving production lines per
  completed difference from 1.53 to 1.45. SDK and verification LOC are unchanged by the review.
- No intentional CCD definition improvement was identified or made. The golden JSON and generated definition semantics
  are unchanged, so no platform-source evidence or separate behavioural-definition commit is required for this slice.

## SDK migration capabilities

Capabilities delivered by Slices 1 to 12 and available for reuse:

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
  metadata;
- exact omission of page-field display order and retention of field authorisation without a generated `CaseField` row;
- significant-event metadata, string-valued TTL increments and profile-specific state metadata and ordering;
- case-role inclusion in case-type authorisation and row-specific access-profile live dates;
- `Number`, `Organisation` and `OrganisationPolicy` field types plus exact legacy searchable, min, max and security
  values; and
- numeric fixed-list codes and labels, omitted fixed-list display order and multiset-preserving fixed-list and
  event-to-complex generation; and
- validated explicit pre-state ordering for legacy definitions whose canonical state sequence differs from the SDK's
  deterministic default sort; and
- standalone event-to-complex rows whose legacy definition intentionally omits the corresponding root
  `CaseEventToFields` row, with flattened properties rejected.

Class-level access is a default for `AuthorisationCaseField` generation only. It does not grant
`AuthorisationComplexType` permissions: complex-type access remains explicit through `builder.grantComplexType(...)`.

These capabilities have backwards-compatible defaults and focused SDK generation, precedence and
invalid-combination tests in `MigrationDefinitionGeneratorTest`, `MigrationArchitectureTest`,
`AuthorisationCaseFieldGeneratorTest` and `AuthorisationCaseTypeGeneratorTest`.

The remaining architecture capability not yet delivered is the build-side ownership manifest and legacy/Java overlay
used for deployable packaging.

Add it only when a coherent slice needs deployable packaging; do not implement it speculatively.

## Recommended next slice: paired Singles referral lifecycle

The recommended thirteenth slice completes the tribunal referral lifecycle for `ET_EnglandWales` and `ET_Scotland`:
`createReferral`, `updateReferral`, `replyToReferral` and `closeReferral`. Keeping the four events together preserves the
create/update/reply/close business boundary and avoids splitting the shared referral fields and nested reply/update
structures across separate conversion commits.

The processed-workbook inventory gives an exact minimum of 406 currently missing rows:

| Profile and case type | `CaseEvent` | `CaseEventToFields` | Event authorisation | Event-to-complex | Complex authorisation | Minimum |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cftlib `ET_EnglandWales` | 4 | 46 | 23 | 18 | 0 | 91 |
| cftlib `ET_Scotland` | 4 | 46 | 23 | 18 | 0 | 91 |
| prod `ET_EnglandWales` | 4 | 46 | 23 | 39 | 0 | 112 |
| prod `ET_Scotland` | 4 | 46 | 23 | 39 | 0 | 112 |
| **Total** | **16** | **184** | **92** | **114** | **0** | **406** |

Expected SDK reuse is typed event and callback metadata, regional and environment profile masks, multi-page
event-field metadata and multiset-preserving nested-event generation; no new SDK capability is presently expected.
Re-inventory all production-only nested rows and the regional callback and permission differences before implementation.
Keep initial consideration, case amendment, TSE applications, party-specific notification actions and work-allocation
maintenance events out of this referral slice.

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
