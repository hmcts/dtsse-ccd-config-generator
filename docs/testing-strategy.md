# Testing Strategy

The SDK's tests are primarily [Cftlib](https://github.com/hmcts/rse-cft-lib)-based functional tests
that run against an isolated full CCD stack.


## Data Migration and CCD Definition Diff Checks

- `./gradlew verifyCcdMigration` exercises the classic and FDW migration scripts end-to-end.
  The tests share the same source data fixture under `scripts/migration-test/`.
- GitHub Actions run each test project's tests and compare generated CCD definitions against master to flag
  any discrepancies.
