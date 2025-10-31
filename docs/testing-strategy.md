# Testing Strategy

The SDK's tests are primarily [Cftlib](https://github.com/hmcts/rse-cft-lib) based [functional tests](../test-projects/e2e/src/cftlibTest/java/uk/gov/hmcts/divorce/cftlib/TestWithCCD.java) that run against an isolated full CCD stack.


## Data Migration & CCD definition diff checks

- `./gradlew verifyCcdMigration` exercises `scripts/test-migrate-ccd-data.sh` providing test coverage for the data migration scripts
- GitHub Actions run each test project's tests & compare generated CCD definitions against master to flag any discrepancies.

