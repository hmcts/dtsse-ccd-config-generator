# CCD data migration task

This page covers the SDK `CcdDataMigrationTask`, a reusable Java migration runner for services
that need to copy large CCD `case_data` and `case_event` tables into their decentralised runtime
database over multiple scheduled runs.

Use this task when the FDW setup has already been created and the one-shot
[`migrate-ccd-data-fdw.sh`](fdw-data-migration.md) script is too operationally risky to run in a
single unattended session.

## What the task does

`CcdDataMigrationTask` is an abstract `Runnable` in the `decentralised-runtime` SDK module. A
service extends it, supplies its normal Spring JDBC database access, and runs it from a scheduler,
cron endpoint, job runner, or manual admin command.

The task:

* validates that `pgcrypto` and the FDW foreign tables exist before copying data
* fails with a link to the FDW setup guide if the FDW setup is missing
* uses a Postgres advisory lock to prevent two instances of the same migration running at once
* maintains a Flyway-managed progress table in the target `ccd` schema
* refuses to run while the decentralised runtime is enabled
* copies source cases in chunks from `fdw_stage.case_data`
* copies all events for each copied case from `fdw_stage.case_event`
* upserts existing target rows so reruns are idempotent
* prepares the target before the first copied chunk by removing the `case_event` FK and event
  revision unique index that block copied history
* disables `case_event` user triggers while migration chunks are still outstanding
* recalculates `case_event.version` and `case_event.case_revision`
* sets `case_data.case_revision` to event count plus the configured revision offset
* restores the FK, unique index, trigger state and `case_event_id_seq` after delta catch-up
* logs start, per-chunk progress, final counts and completion state

## Prerequisites

Before the task can run, complete the FDW setup described in
[`fdw-data-migration.md`](fdw-data-migration.md). In particular, the target application database
must have:

* the `postgres_fdw` and `pgcrypto` extensions
* readable foreign tables for the source CCD tables:
  * `fdw_stage.case_data`
  * `fdw_stage.case_event`
* writable target runtime tables:
  * `ccd.case_data`
  * `ccd.case_event`
* permission for the application database user to run `SET LOCAL session_replication_role = replica`
* permission to drop and recreate the `case_event` FK and event revision unique index

The task assumes the service already depends on the decentralised runtime SDK and has a configured
Spring `DataSource`.

The application must also be running with the decentralised runtime disabled. The task checks
`ccd.sdk.decentralised` through Spring `Environment` when supplied, or falls back to
`CCD_SDK_DECENTRALISED` / the `ccd.sdk.decentralised` system property. If that value is `true`, the
task fails before taking a lock or preparing the target tables.

## Progress and reruns

The decentralised runtime Flyway migrations create `ccd.ccd_data_migration_progress`. The table
records:

* task name
* migration configuration fingerprint
* target schema, FDW schema, case type IDs and case revision offset
* current phase: initial load or delta catch-up
* current source window
* last copied source modified timestamp in delta mode
* last copied source `case_data.id`
* total copied batches, cases and events
* whether the target constraints/triggers are currently prepared for an unfinished migration

During the initial phase, each run copies cases where source `case_data.id` is greater than the last
processed ID and the source `last_modified`/`created_date` is inside the current window. When the
initial window is exhausted, the task moves to delta mode.

During delta mode, the task repeatedly opens a new source window and copies cases modified since the
previous completed window. Delta progress is ordered by
`coalesce(last_modified, created_date), id`, not just by ID, so an older case ID updated later in the
same window is still picked up. This lets a service run the task overnight, stop cleanly, then
continue the next day without starting again.

Saved progress is tied to the migration definition. A later run with the same `taskName` must use
the same target schema, FDW schema, case type IDs and case revision offset. Changing operational
limits such as `batchSize`, `maxBatchesPerRun`, `maxRunTime`, `runUntil` or `deltaOverlap` is allowed,
but changing the migration definition fails fast. Use a new `taskName` for a different migration, or
manually reset the progress row only after confirming the previous migration state is no longer
needed.

The target is prepared only when the task has a real batch to copy. If a run stops because of
`maxBatchesPerRun`, `maxRunTime`, or `runUntil`, the progress table keeps `target_prepared=true` and
the task does not rebuild the `case_event` revision index at the end of that partial run. The next
invocation resumes with the target still prepared. Once the task reaches delta catch-up, it restores
the FK, revision unique index and user triggers, then records `target_prepared=false`.

## Runtime limits

The task always finishes the current chunk before checking stop conditions. It does not interrupt a
chunk mid-transaction.

Configure one or more limits:

* `batchSize`: number of source cases per chunk
* `maxBatchesPerRun`: maximum chunks in one invocation
* `maxRunTime`: maximum elapsed runtime for one invocation
* `runUntil`: fixed date/time after which the invocation should stop
* `deltaOverlap`: overlap applied when opening delta windows, default 15 minutes
* `validationMode`: when to run full table validation, default `DELTA_ONLY`

If both `maxRunTime` and `runUntil` are set, the earlier deadline is used. The returned
`CcdDataMigrationRunResult` includes `stoppedByTimeLimit` so scheduled jobs can distinguish a
planned time stop from a caught-up migration.

The delta overlap intentionally reprocesses a small amount of recent data. Upserts are idempotent,
and the overlap protects against late-committing transactions whose `last_modified` timestamp falls
just before the previous completed delta boundary.

The SDK default `batchSize` is deliberately conservative, but production migrations should tune it
with a lower environment rehearsal. Larger batches reduce transaction, FDW round-trip and progress
update overhead, so they are usually faster when cases have a modest number of events. Start with
`500` or `1_000` cases per batch, then adjust using the observed per-chunk duration and event count.
Use a smaller batch size if individual cases have very large event histories or if chunks approach
the maximum runtime window.

## Full validation

Full validation logs final counts, checks for orphaned `case_event` rows, and verifies
`case_data.case_revision` against the migrated events. These checks scan the target CCD tables for
the configured case types, so large migrations may need to avoid running them during every initial
load window.

Configure `validationMode` with one of:

* `DELTA_ONLY`: run full validation while the saved progress is in delta mode. This is the default.
* `ALWAYS`: run full validation after every invocation that acquires the migration lock.
* `NEVER`: skip full validation. Use this only when validation is handled separately.

## Example Spring integration

This example wires an ET migration task that copies two case types in batches of 500 cases and stops
after either 4 hours or 06:00 UTC, whichever happens first.

```java
package uk.gov.hmcts.ethos.replacement.docmosis.service.migration;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationTask;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationTaskOptions;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationValidationMode;

@Component
public class EtCcdDataMigrationTask extends CcdDataMigrationTask {

  public EtCcdDataMigrationTask(
      NamedParameterJdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      Environment environment
  ) {
    super(
        jdbcTemplate,
        transactionManager,
        CcdDataMigrationTaskOptions.builder(List.of("ET_EnglandWales", "ET_Scotland"))
            .taskName("et-ccd-data-migration")
            .targetSchema("ccd")
            .fdwSchema("fdw_stage")
            .batchSize(500)
            .maxBatchesPerRun(1_000)
            .maxRunTime(Duration.ofHours(4))
            .runUntil(LocalDateTime.of(LocalDate.now(ZoneOffset.UTC), LocalTime.of(6, 0)))
            .deltaOverlap(Duration.ofMinutes(15))
            .validationMode(CcdDataMigrationValidationMode.DELTA_ONLY)
            .caseRevisionOffset(1_000_000_000L)
            .build(),
        environment
    );
  }
}
```

Run the task from a scheduler owned by the service. Keep the schedule disabled by default and enable
it only for the migration window.

```java
package uk.gov.hmcts.ethos.replacement.docmosis.service.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationRunResult;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "migration.ccd-data.enabled", havingValue = "true")
public class EtCcdDataMigrationScheduler {

  private final EtCcdDataMigrationTask migrationTask;

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

Example configuration:

```yaml
migration:
  ccd-data:
    enabled: false
    cron: "0 */10 20-23,0-5 * * *"
```

## Operational guidance

Run a lower environment rehearsal first with representative data volumes. Check the task logs for:

* start configuration
* FDW validation
* per-chunk case and event counts
* final target counts by case type
* `caughtUp=true` when no more eligible data remains in the current delta window
* `stoppedByTimeLimit=true` when a run deliberately stops after an overnight window
* `target_prepared=true` in `ccd.ccd_data_migration_progress` while a partial migration is still
  waiting to resume

Use the rehearsal to choose the production `batchSize`. The target is to keep chunks large enough to
avoid excessive per-batch overhead, but small enough that a single chunk completes comfortably before
the end of the overnight window. If the logs show consistently small event counts and short chunk
times, increase the batch size. If one chunk takes too long or contains unusually large event
histories, reduce it before production.

For production, agree the FDW setup and database permissions with PlatOps before enabling the
scheduled task. A partial migration deliberately leaves the `case_event` FK, event revision unique
index and user triggers prepared until delta catch-up has completed.

That is safe only while the target runtime tables are migration-owned. Do not enable decentralised
case writes, admin jobs, repair scripts, test endpoints, or any other writer that inserts or updates
target `ccd.case_data` / `ccd.case_event` rows while `target_prepared=true`. Postgres will not replay
disabled triggers for rows written during that period, and the missing FK/index mean normal integrity
checks are not enforced until the task restores them. If restoration fails, stop the application and
restore the `case_event` FK, event revision unique index and user triggers before allowing case
writes.
