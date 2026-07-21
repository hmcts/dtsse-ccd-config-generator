# Testing style guide

## Test behaviour rather than implementation

Mocks must be avoided unless there is no practical alternative to achieve a required test scenario.

The SDK's tests are primarily [Cftlib](https://github.com/hmcts/rse-cft-lib)-based functional tests that run against an isolated full CCD stack.

Prefer Spring integration tests or `e2e:cftlibTest` full-stack tests for SDK behaviour.

Prefer adding new assertions to existing tests where appropriate.

Config generator changes should be covered by extending the existing JSON diff-based golden files.

Changes that deviate from this strategy must provide justification or will not be accepted.

## Data Migration and CCD Definition Diff Checks

- `./gradlew verifyCcdMigration` exercises the classic and FDW migration scripts end-to-end.
  The tests share the same source data fixture under `scripts/migration-test/`.
- GitHub Actions run each test project's tests and compare generated CCD definitions against master to flag
  any discrepancies.
