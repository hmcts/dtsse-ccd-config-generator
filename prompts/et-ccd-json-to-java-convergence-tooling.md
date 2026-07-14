# ET CCD JSON-to-Java convergence tooling prompt

Use this prompt when implementing or reviewing an ET CCD JSON-to-Java migration slice. It describes how to measure
semantic convergence, how to interpret the result and when the committed snapshot may be updated.

Read these documents before changing a definition:

- [`et-ccd-json-to-java-style-guide.md`](et-ccd-json-to-java-style-guide.md)
- [`et-ccd-json-to-java-architecture-plan.md`](et-ccd-json-to-java-architecture-plan.md)
- [`../test-projects/et-ccd-callbacks/ccd-definitions/tools/et-migration-progress/README.md`](../test-projects/et-ccd-callbacks/ccd-definitions/tools/et-migration-progress/README.md)

## Objective

Move ET CCD definition ownership from the golden JSON to typed Java while making progress measurable and reviewable.
The primary progress number is the reduction in XLSX row differences from the immutable initial baseline. Physical Java
line counts are a secondary guardrail against replacing a concise definition with an unnecessarily large Java model.

Do not judge a slice by compilation or generated JSON appearance alone. The slice is acceptable only when its Java rows
are exact matches for the corresponding processed golden workbook rows in every applicable environment.

Callback handler registration, controllers and runtime callback routing are outside this work. Existing callback URLs
may be represented as definition-generation metadata only.

## Non-negotiable rules

1. Treat `test-projects/et-ccd-callbacks/ccd-definitions` as the golden definition. Do not edit golden JSON or XLSX
   templates to make a comparison pass.
2. Generate Java-only definitions. Do not start the ET application or embedded CCD stack to measure convergence.
3. Compare processed XLSX output, not source JSON files. ET's definition processor applies environment selection, sheet
   aliases, access-control transformations and template column rules which a direct JSON diff would miss.
4. Run both `cftlib` and `prod` comparisons for all three jurisdiction bundles: `admin`, `england-wales` and `scotland`.
5. Preserve duplicate row occurrences. Rows are compared as multisets, so duplicate definitions must neither disappear
   nor be collapsed accidentally.
6. Never rewrite the baseline to improve the percentage. The initial baseline is 52,227 remaining differences.
7. Do not run the snapshot-update task merely to make verification green. First understand every changed metric and
   inspect the generated output.
8. Do not commit generated workbooks, staged JSON, dependency installation state or ordinary build output.

## Commands

Run commands from the root of `dtsse-ccd-config-generator-json-to-java`.

Generate the migration-only Java JSON:

```shell
./gradlew :et:generateEtMigrationJson
```

Run the comparator unit tests:

```shell
./gradlew :et:testEtMigrationProgress
```

Generate both sets of workbooks, calculate progress and verify that the result matches the committed snapshot:

```shell
./gradlew :et:etMigrationProgress
```

After an intentional, reviewed migration change, update the committed snapshot:

```shell
./gradlew :et:updateEtMigrationProgress
```

`etMigrationProgress` is part of the ET `check` lifecycle. Prefer running it directly while iterating because its report
is quicker to find and interpret.

## What the harness does

The harness performs this pipeline:

```text
generation-only Java configuration
  -> JSON grouped by the eight ET case types
  -> staged into admin, England/Wales and Scotland inputs
  -> existing ET JSON-to-XLSX processor
  -> Java-only cftlib and prod workbooks
  -> sheet-by-sheet multiset comparison with golden workbooks
  -> convergence and Java-line report
```

Golden workbooks use the existing ET JSON and normal templates. Java-only workbooks use copies of the same templates
with seeded definition rows cleared while retaining the real sheet names and headers. This prevents template content
from being mistaken for Java progress.

The harness maps the generated case types as follows:

| Bundle | Case types |
| --- | --- |
| `admin` | `ET_Admin`, `Pre_Hearing_Deposit` |
| `england-wales` | `ET_EnglandWales`, `ET_EnglandWales_Listings`, `ET_EnglandWales_Multiple` |
| `scotland` | `ET_Scotland`, `ET_Scotland_Listings`, `ET_Scotland_Multiple` |

Unknown generated case-type directories fail staging. Generated values in columns which the XLSX template would
discard also fail validation rather than being silently ignored.

## How rows are classified

Rows are canonicalised using the columns present in the target workbook sheet and compared without relying on row
order.

- **Exact**: one Java row has the same identity and all the same values as one golden row.
- **Changed**: Java and golden rows have the same CCD identity but one or more non-identity cells differ.
- **Missing**: a golden row has no Java counterpart.
- **Unexpected**: a Java row has no golden counterpart.
- **Changed cells**: the number of differing non-identity cell values within changed rows; this is diagnostic detail,
  not an additional remaining-difference count.

An external CCD identity change is deliberately classified as one missing row plus one unexpected row. This makes a
renamed event, field, role or state visible as deletion of the old contract and addition of a new contract.

Duplicate occurrences are matched independently. Two identical golden rows and one matching Java row result in one
exact row and one missing row.

For a comparison scope:

```text
remaining = changed + missing + unexpected
completed = baseline remaining differences - current remaining
completion percentage = completed / baseline remaining differences * 100
```

The percentage is calculated across both environments and all jurisdiction bundles. A Java row which is valid in both
`cftlib` and `prod` can therefore remove one difference from each environment.

## Expected workflow for a migration slice

1. Choose a coherent vertical slice and identify all rows it should own, including fields, states, events, tabs,
   searches, complex types and authorisation.
2. Record the expected golden row count per environment before implementing it.
3. Add the typed Java definition under the generation-only `ccdMigration` source set, plus reusable SDK capabilities
   only where the existing typed API is a poor semantic fit.
4. Run `generateEtMigrationJson` and inspect the JSON for the intended case type.
5. Run `etMigrationProgress`. A stale-snapshot failure is expected while the committed snapshot still describes the
   previous reviewed state; use the report written under the build directory for diagnosis.
6. Resolve all changed and unexpected rows. Missing rows outside the selected slice are expected; missing rows owned by
   the slice are not.
7. Confirm that the exact-row increase equals the expected slice size in every applicable environment.
8. Review Java line growth. Prefer reusable domain-oriented APIs and shared configuration modules over repeated builder
   calls or a generic Java representation of spreadsheet rows.
9. Run the relevant SDK tests, ET compilation, Checkstyle and PMD checks.
10. Run `updateEtMigrationProgress`, review the snapshot diff, then rerun `etMigrationProgress` to prove that the
    committed snapshot is current.

An accepted slice should normally have zero changed and zero unexpected rows. Do not accept approximate rows to make
the completion percentage move: only exact generated rows remove golden differences safely.

## Java line-count guardrail

The snapshot records physical Java lines for:

- ET production and generation code (`etMain`);
- ET verification code (`etVerification`);
- SDK production code (`sdkMain`); and
- SDK verification code (`sdkVerification`).

It also reports the total production-line delta from baseline and production lines per completed difference. These are
review signals, not automatic limits. A foundational SDK feature may make an early slice look expensive but should be
reused by later slices. Investigate line growth when:

- regional variants repeat nearly identical configuration;
- an SDK limitation forces forwarding classes or boilerplate;
- Java objects reproduce arbitrary spreadsheet columns instead of domain concepts;
- fixed-list or access-control definitions repeat the same identifiers; or
- verification code grows without protecting a meaningful semantic boundary.

Do not reduce line counts by weakening types, hiding regional differences, removing tests or moving spreadsheet-shaped
data into generic maps. If the SDK API is a poor fit, improve it with a narrow, reusable typed capability and regression
tests.

## Artefacts and source of truth

The committed snapshot is:

```text
test-projects/et-ccd-callbacks/ccd-definitions/migration-progress.json
```

Generated diagnostic artefacts are beneath:

```text
test-projects/et-ccd-callbacks/build/ccd-migration/
```

Important generated locations include:

```text
java-definitions/                 Java-generated JSON by case type
staged-java/                      JSON staged into jurisdiction workbook layouts
workbooks/<profile>/golden/       Processed golden workbooks
workbooks/<profile>/java/         Java-only workbooks
migration-progress.json           Detailed result from the latest local run
```

These generated artefacts are disposable and must not be committed. The reviewed
`ccd-definitions/migration-progress.json` is the only committed metric state.

The tooling source is under:

```text
test-projects/et-ccd-callbacks/ccd-definitions/tools/et-migration-progress/
```

If comparison behaviour changes, add or update comparator tests and explain whether the immutable baseline remains
comparable. Do not silently change identity rules, canonicalisation or duplicate handling mid-migration.

## Required handoff

When reporting a completed slice, state:

- the case type or feature converted;
- exact rows added in each applicable environment;
- total completion percentage and completed/remaining differences;
- changed and unexpected row counts;
- ET and SDK production Java-line deltas;
- production lines per completed difference;
- checks run and any repository-wide pre-existing blockers; and
- confirmation that golden definitions were not edited.

Use a compact result such as:

```text
Slice: <case type / feature>
Exact: <increase> cftlib, <increase> prod
Completion: <percentage>% (<completed> removed, <remaining> remaining)
Changed/unexpected: <changed>/<unexpected>
Java: ET <delta>, SDK <delta>, <ratio> production lines per completed difference
Verification: <commands and result>
Golden definitions: unchanged
```
