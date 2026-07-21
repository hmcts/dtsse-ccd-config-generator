# CCD data migration task

This page covers the SDK `CcdDataMigrationTask`, a reusable Java migration runner for services that
need to copy large CCD `case_event` history, event significant items and final `case_data` rows into
their decentralised runtime database.

Use this task after the FDW setup in [`fdw-data-migration.md`](fdw-data-migration.md) has created
the FDW server, user mapping, and at least one of the CCD source foreign tables. The task creates
any missing `fdw_stage.case_data`, `fdw_stage.case_event`, or
`fdw_stage.case_event_significant_items` foreign tables from the same existing FDW table options so
environments that were set up before a table was added can self-heal. The task is designed for
repeated event preloads before downtime, followed by an explicit operator-run cutover after source
writes for the selected case types have been frozen.

## Modes

The task has one implementation with explicit modes:

* `PRELOAD_EVENTS`: scheduled before cutover. Copies immutable source events by event ID high-water
  mark and inserts provisional parent `case_data` rows so the target FK remains valid.
* `CUTOVER`: explicit operator action during downtime. Captures the cutover event high-water mark,
  copies the final event delta, copies linked `case_event_significant_items` in a single set-based
  query, reconciles target `case_data` with source, sets final case revisions, resets sequences,
  validates, and marks the migration complete.
* `VALIDATE_ONLY`: runs final source-vs-target validation without copying data.

Do not switch to `CUTOVER` automatically from a scheduler. The operator must first freeze source
writes for the selected case types and wait for in-flight source transactions to drain.

If cutover is paused or interrupted before completion, switching back to `PRELOAD_EVENTS` is
supported. The task keeps its event and significant-item progress, returns the status to `PRELOAD`,
and clears the captured cutover event high-water mark. A later `CUTOVER` invocation therefore
captures a fresh high-water mark after source writes have been frozen again.

## Safety Model

The preload path keeps the target tables constraint-valid after every committed batch:

* the `case_event -> case_data` FK stays in place
* the `case_event_significant_items -> case_event` FK stays in place
* the unique `(case_data_id, case_revision)` index stays in place
* `case_event.version` and `case_event.case_revision` are calculated before insert
* `case_event` user triggers are not disabled
* resume position is stored as a source `case_event.id` window position after each committed batch

Preloaded `case_data` is provisional. Its purpose is to satisfy the event FK. Every copied event
batch inserts missing parent source `case_data` rows for that batch. Significant items are copied
only during `CUTOVER`, using one `insert into ... select` query that reads source significant items
and joins through already migrated target events up to the captured cutover event high-water mark.
`CUTOVER` stages every selected source `case_data` row in its final refresh transaction, upserts that
stable set into the target, and deletes target cases in the configured jurisdiction and case types
that are no longer present in source. The existing foreign-key cascades remove their case events and
event dependants. It then sets
`case_data.case_revision = max(case_event.case_revision) + caseRevisionOffset`.

The source write freeze must include retain-and-dispose work, and operators must wait for its
in-flight transactions to drain. A source case deleted after the cutover snapshot cannot be observed
by that cutover run.

The runtime `case_data` revision trigger is deliberately disabled only inside the cutover refresh
transaction so the final source-derived revision can be written. The trigger is re-enabled before the
transaction completes.

The `ccd.case_data` Elasticsearch enqueue trigger is disabled while migration work is in progress
because it fires after inserts and updates on `ccd.case_data` and would otherwise populate
`ccd.es_queue` with stale revision entries. Existing queue rows for the migrated case types are
deleted when the task starts migration work because those case types do not use the queue until after
cutover. Final validation requires the migrated case types to have no queue rows, and the trigger is
re-enabled in the same transaction that marks `CUTOVER` complete.

## Progress

The decentralised runtime Flyway migration creates `ccd.ccd_data_migration_progress` with minimal
state:

* `task_name`
* `config_hash`
* `status`: `PRELOAD`, `CUTOVER`, or `COMPLETE`
* `cutover_event_hwm`
* `source_event_hwm`
* timestamps

The migration configuration hash covers the migration identity. Runtime limits such as
`eventIdWindowSize`, `significantItemIdWindowSize`, `maxBatchesPerRun`, `maxRunTime`, and `mode` can change between invocations.
If the migration identity changes after a task has already created a progress row, the task fails
fast rather than resuming under different source filters. Operators must either keep the same
identity configuration or explicitly reset/use a new `task-name` after confirming the target state.

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
    source-jurisdiction: EMPLOYMENT
    event-id-window-size: 1000000
    significant-item-id-window-size: 100000
    max-batches-per-run: 100
    max-run-time: 4h
    case-revision-offset: 1000000000
    fdw-additional-select-grantee: DTS JIT Access et DB Reader SC
```

`fdw-additional-select-grantee` is optional. When set, the Java task grants the role local access to
query the existing FDW tables after validating them: `USAGE` on the FDW staging schema, `USAGE` on
the foreign server, and `SELECT` on `fdw_stage.case_data`, `fdw_stage.case_event`, and
`fdw_stage.case_event_significant_items`. The role must already have a user mapping for the FDW
server. Create it during FDW setup with `FDW_ADDITIONAL_GRANTEE` or have Platform Operations
create it manually. The Java task does not create one because the mapping contains source database
credentials. Leave it blank to skip the extra grant. As an environment variable, use
`CCD_DATA_MIGRATION_FDW_ADDITIONAL_SELECT_GRANTEE`.

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

After cutover, the task verifies that no Elasticsearch queue rows remain for the selected case types
before re-enabling the queue trigger. Full-table source and target counts are not performed because
they are unbounded on production-scale CCD data.

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
  -Dccd.data-migration.perf.event-id-window-size=1000 \
  -Dccd.data-migration.perf.max-seconds=900
```
