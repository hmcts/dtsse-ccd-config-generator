# CCD data migration task

This page covers the SDK `CcdDataMigrationTask`, a reusable Java migration runner for services that
need to copy large CCD `case_event` history and final `case_data` rows into their decentralised
runtime database.

Use this task after the FDW setup in [`fdw-data-migration.md`](fdw-data-migration.md) has been
created. The task is designed for repeated event preloads before downtime, followed by an explicit
operator-run cutover after source writes for the selected case types have been frozen.

## Modes

The task has one implementation with explicit modes:

* `PRELOAD_EVENTS`: scheduled before cutover. Copies settled immutable source events by event ID
  high-water mark and inserts provisional parent `case_data` rows so the target FK remains valid.
* `CUTOVER`: explicit operator action during downtime. Captures the cutover event high-water mark,
  repairs any late lower-ID event gaps, copies the final event delta, inserts any missing
  `case_data`, sets final case revisions, resets sequences, validates, and marks the migration
  complete.
* `VALIDATE_ONLY`: runs final source-vs-target validation without copying data.

Do not switch to `CUTOVER` automatically from a scheduler. The operator must first freeze source
writes for the selected case types and wait for in-flight source transactions to drain.

## Safety Model

The preload path keeps the target tables constraint-valid after every committed batch:

* the `case_event -> case_data` FK stays in place
* the unique `(case_data_id, case_revision)` index stays in place
* `case_event.version` and `case_event.case_revision` are calculated before insert
* `case_event` user triggers are not disabled
* progress advances only after the parent/event inserts commit

Preloaded `case_data` is provisional. Its purpose is to satisfy the event FK. Every copied event
batch upserts the parent source `case_data` rows for that batch. `CUTOVER` inserts any selected
source cases that do not already exist in the target, then sets
`case_data.case_revision = max(case_event.case_revision) + caseRevisionOffset`. Final validation
compares source-owned `case_data` columns back to the source before the task can mark the migration
complete.

The runtime `case_data` revision trigger is deliberately disabled only inside the cutover refresh
transaction so the final source-derived revision can be written. The trigger is re-enabled before the
transaction completes.

## Progress

The decentralised runtime Flyway migration creates `ccd.ccd_data_migration_progress` with minimal
state:

* `task_name`
* `config_hash`
* `status`: `PRELOAD`, `CUTOVER`, or `COMPLETE`
* `loaded_event_hwm`
* `cutover_event_hwm`
* timestamps

The migration configuration hash covers the migration identity. Runtime limits such as
`eventBatchSize`, `maxBatchesPerRun`, `maxRunTime`, `settlementInterval`, and `mode` can change
between invocations.

## Settlement

`PRELOAD_EVENTS` does not use raw `max(case_event.id)`. Source sequence IDs are allocation-order, not
commit-order, so the task uses a settled event high-water mark:

```sql
ce.created_date < clock_timestamp() - settlementInterval
```

`settlementInterval` must be greater than the maximum source write transaction lifetime plus a
safety margin. If that lifetime is not enforced, cutover validation and the late-event repair sweep
are the safety net.

## Configuration

Common Spring Boot properties:

```yaml
ccd:
  data-migration:
    enabled: true
    task-name: et-ccd-data-migration
    mode: PRELOAD_EVENTS
    case-type-ids:
      - ET_EnglandWales
      - ET_Scotland
    event-batch-size: 10000
    max-batches-per-run: 100
    max-run-time: 4h
    settlement-interval: 30m
    validation-mode: NEVER
    case-revision-offset: 1000000000
```

For cutover, run the same task with:

```yaml
ccd:
  data-migration:
    mode: CUTOVER
```

For an independent validation run:

```yaml
ccd:
  data-migration:
    mode: VALIDATE_ONLY
```

## Scheduler Example

The SDK can create the task bean from `ccd.data-migration.*`. The service owns the scheduler or
operator endpoint that calls it.

```java
package uk.gov.hmcts.ethos.replacement.docmosis.service.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationRunResult;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationTask;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "migration.ccd-data.enabled", havingValue = "true")
public class EtCcdDataMigrationScheduler {

  private final CcdDataMigrationTask migrationTask;

  @Scheduled(cron = "${migration.ccd-data.cron}")
  public void runMigration() {
    CcdDataMigrationRunResult result = migrationTask.runMigration();

    log.info(
        "ET CCD data migration result lockAcquired={} batches={} cases={} events={} caughtUp={} timeLimited={}",
        result.lockAcquired(),
        result.batchesProcessed(),
        result.casesProcessed(),
        result.eventsProcessed(),
        result.caughtUp(),
        result.stoppedByTimeLimit()
    );
  }
}
```

Keep the scheduler disabled by default. Enable scheduled `PRELOAD_EVENTS` only during the preload
period. Run `CUTOVER` explicitly during the agreed downtime window.

## Validation

Final validation checks:

* no selected source `case_data` rows are missing from target
* no selected source `case_event` rows up to `cutover_event_hwm` are missing from target
* source-owned `case_data` and `case_event` columns match target
* no orphaned target `case_event` rows exist for the selected case types
* `case_data.case_revision` matches `max(case_event.case_revision) + caseRevisionOffset`
* target event IDs reach the required high-water mark

`PRELOAD_EVENTS` can optionally check that no source events are missing up to `loaded_event_hwm` by
setting `validationMode=ALWAYS`. The default is `NEVER` to avoid expensive scans on every scheduled
preload. `CUTOVER` and `VALIDATE_ONLY` always run final validation.

## Performance Harness

The SDK integration tests include a configurable FDW migration harness that seeds a synthetic source
dataset, runs `PRELOAD_EVENTS` and `CUTOVER` against the real decentralised runtime Flyway schema,
and verifies migrated case/event counts. By default it seeds 100,000 cases and 1,000,000 events.

```bash
./gradlew -p sdk :decentralised-runtime:test \
  --tests '*CcdDataMigrationTaskIntegrationTest.migratesSeededDatasetWithinPerfHarnessLimit'
```

Override the dataset with system properties:

```bash
./gradlew -p sdk :decentralised-runtime:test \
  --tests '*CcdDataMigrationTaskIntegrationTest.migratesSeededDatasetWithinPerfHarnessLimit' \
  -Dccd.data-migration.perf.cases=10000 \
  -Dccd.data-migration.perf.events-per-case=5 \
  -Dccd.data-migration.perf.event-batch-size=1000 \
  -Dccd.data-migration.perf.max-seconds=900
```
