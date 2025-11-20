# Testing Strategy

The SDK's tests are primarily [Cftlib](https://github.com/hmcts/rse-cft-lib)-based [functional tests](../test-projects/e2e/src/cftlibTest/java/uk/gov/hmcts/divorce/cftlib/TestWithCCD.java) that run against an isolated full CCD stack.


## Data Migration and CCD Definition Diff Checks

- `./gradlew verifyCcdMigration` exercises `scripts/test-migrate-ccd-data.sh` (validation + apply + cleanup) to cover the migration scripts end-to-end.
- GitHub Actions run each test project's tests and compare generated CCD definitions against master to flag any discrepancies.
