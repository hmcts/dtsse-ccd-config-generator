# FDW data migration guide

This page covers the FDW-based CCD data migration scripts:

* `scripts/setup-ccd-data-fdw.sh`
* `scripts/migrate-ccd-data-fdw.sh`

Use this approach when the target application database can read the central CCD database through
`postgres_fdw`. The setup script creates the FDW objects once. The migration script then assumes
those foreign tables already exist and only reads from them.

## Overview

```mermaid
flowchart LR
    subgraph CCD["CCD central database"]
      A["public.case_data"]
      B["public.case_event"]
      G["public.case_event_significant_items"]
    end

    subgraph APP["Application database"]
      C["fdw_stage.case_data"]
      D["fdw_stage.case_event"]
      H["fdw_stage.case_event_significant_items"]
      E["ccd.case_data"]
      F["ccd.case_event"]
      I["ccd.case_event_significant_items"]
    end

    A -->|postgres_fdw| C
    B -->|postgres_fdw| D
    G -->|postgres_fdw| H
    C -->|insert/update| E
    D -->|insert/update| F
    H -->|insert/update| I
```

## Prerequisites

### Database extensions

The target Postgres server must allow both extensions before setup is run:

* `postgres_fdw`
* `pgcrypto`

For Azure Flexible Server this normally means `azure.extensions` includes both values, for example:

```hcl
{
  name  = "azure.extensions"
  value = "postgres_fdw,pgcrypto"
}
```

After the extensions are allowed, the setup script creates them in the target database:

```sql
create extension if not exists postgres_fdw;
create extension if not exists pgcrypto;
```

### Database access

You need:

* a target application database connection string with permission to create extensions, schemas,
  FDW servers, user mappings and foreign tables for setup
* a source CCD database user with read access to `case_data`, `case_event`, and
  `case_event_significant_items`
* a target application database user with write access to `ccd.case_data`, `ccd.case_event`, and
  `ccd.case_event_significant_items`
* permission for the migration user to run `SET LOCAL session_replication_role = replica`
* network connectivity from the target database to the source CCD database
* `psql` available on the machine running the scripts

The application should be shuttered or read-only for the migrating case types before the final
`--apply` migration run.

## Operational cutover runbook

The FDW setup and initial validation should be completed before the migration window. The final
migration must run while source writes are frozen so that the source and target cannot diverge
during cutover.

### Service team preparation

1. Prepare a shuttered CCD definition that blocks normal user access while retaining only the
   punch-through access needed to verify the migration:
   * a dedicated test superuser for post-migration smoke tests
   * `R` access for `caseworker-wa-task-configuration` when Work Allocation verification is required
2. Prepare any service frontends for shuttering. See the
   [Platform shuttering guide](https://hmcts.github.io/cloud-native-platform/path-to-live/shutter.html#shutter-implementation-and-design).
3. Prepare the CCD Flux PR that marks the case types as decentralised, for example the
   [Special Tribunals cutover PR](https://github.com/hmcts/cnp-flux-config/pull/42025).
4. Prepare a separate rollback PR that restores the centralised configuration, for example the
   [Special Tribunals rollback PR](https://github.com/hmcts/cnp-flux-config/pull/42030).
5. Agree the smoke-test cases, expected Work Allocation behaviour and go/no-go owners before the
   migration window.

### Platform Operations preparation

1. Complete [Phase 1](#phase-1-set-up-fdw-objects), including the required user mappings and grants,
   before the migration window.
2. Make the setup and migration scripts available on the bastion or other approved runner:

   ```bash
   scp ./scripts/setup-ccd-data-fdw.sh <bastion-host>:
   scp ./scripts/migrate-ccd-data-fdw.sh <bastion-host>:
   ```

3. Prepare the destination connection and case type environment variables described in
   [Phase 2](#phase-2-run-the-migration).
4. Run the migration script without `--apply` and resolve any validation failures before cutover:

   ```bash
   ./scripts/migrate-ccd-data-fdw.sh
   ```

5. Estimate the required shutter window from a representative environment. See
   [Expected runtime](#expected-runtime) for an example; production runtime depends on the number of
   cases and events being migrated.

### On the night

1. Create or identify test cases in central CCD for post-migration verification.
2. Shutter the application and import the prepared shuttered CCD definition.
3. Confirm writes for the migrating case types are frozen and allow in-flight source transactions
   to finish.
4. Run the final FDW migration:

   ```bash
   unset DELTA_SINCE
   ./scripts/migrate-ccd-data-fdw.sh --apply
   ```

   If a full copy has already been performed and an agreed delta timestamp is being used, run the
   final delta instead:

   ```bash
   export DELTA_SINCE='2026-04-30 10:00:00'
   ./scripts/migrate-ccd-data-fdw.sh --apply
   ```

   Services using `CcdDataMigrationTask` should follow the preload and explicit `CUTOVER` process in
   the [CCD data migration task guide](ccd-data-migration-task.md) instead of invoking the shell
   migration script.
5. Merge the prepared CCD Flux PR to mark the case types as decentralised.
6. Complete the [post-migration checks](#post-migration-checks), including Work Allocation checks
   where applicable.
7. Make the go/no-go decision:
   * **Go:** unshutter the service.
   * **No-go:** merge the prepared CCD Flux rollback PR, confirm traffic is routed back to central
     CCD, and then unshutter the service.

The migration is non-destructive: the source cases remain in central CCD. Rollback changes the CCD
configuration back to the centralised path; it does not require restoring source case data.

## Phase 1: Set up FDW objects

Run `setup-ccd-data-fdw.sh` once against the target application database. This is the part likely
to be run by PlatOps because it needs elevated database privileges.

Required environment variables:

```bash
export DST_DSN='postgresql://target-user:target-pass@target-host:5432/target-db?sslmode=require'
export SRC_HOST='source.postgres.database.azure.com'
export SRC_PORT='5432'
export SRC_DB='ccd_data_store'
export SRC_SCHEMA='public'
export SRC_USER='readonly_user'
export SRC_PASSWORD='...'
export SRC_PASSWORD_REQUIRED='true'
export SRC_SSLMODE='require'
```

Optional environment variables:

```bash
export DST_SCHEMA='ccd'                    # defaults to ccd
export FDW_SCHEMA='fdw_stage'              # defaults to fdw_stage
export FDW_SERVER='src_ccd_server'         # defaults to src_ccd_server
export LOCAL_USER_SQL='current_user'       # role that will run the migration
export FDW_ADDITIONAL_GRANTEE='DTS JIT Access et DB Reader SC'
```

Validate the setup configuration without creating anything:

```bash
./scripts/setup-ccd-data-fdw.sh
```

Create or replace the FDW setup:

```bash
./scripts/setup-ccd-data-fdw.sh --apply
```

The setup script creates:

* the `postgres_fdw` and `pgcrypto` extensions
* the FDW staging schema, default `fdw_stage`
* an FDW server pointing at the source CCD database
* a user mapping for `LOCAL_USER_SQL` using `SRC_USER` and `SRC_PASSWORD`
* when `FDW_ADDITIONAL_GRANTEE` is set, another user mapping for that role using the same
  source credentials
* foreign tables:
  * `fdw_stage.case_data`
  * `fdw_stage.case_event`
  * `fdw_stage.case_event_significant_items`

The foreign tables are created with `fetch_size '10000'` so large reads do not use the
`postgres_fdw` default of 100 rows per cursor fetch. If the FDW objects were created before this
option existed, recreate them with `setup-ccd-data-fdw.sh --apply` before running a large migration.
* grants for `LOCAL_USER_SQL`
* when `FDW_ADDITIONAL_GRANTEE` is set, grants for that additional role

`SRC_PASSWORD_REQUIRED` defaults to `true`, matching `postgres_fdw`'s default safety check for
non-superusers. Only set it to `false` for local/test FDW servers that use trust authentication and
do not request a password.

## Phase 2: Run the migration

The migration script only reads from the FDW foreign tables. It does not create or write to
`fdw_stage`. The Java `CcdDataMigrationTask` is a little more forgiving: if the FDW server and at
least one of the expected source foreign tables already exist, it creates any missing
`fdw_stage.case_data`, `fdw_stage.case_event`, or `fdw_stage.case_event_significant_items` foreign
tables from the same server, source schema, and fetch size options.

For services using the Java task, set `ccd.data-migration.fdw-additional-select-grantee` or
`CCD_DATA_MIGRATION_FDW_ADDITIONAL_SELECT_GRANTEE` to grant an additional team-specific reader role
enough access to query the FDW tables, for example `DTS JIT Access et DB Reader SC`. The task grants
schema usage, foreign server usage, and table select. The role must already have a user mapping for
the FDW server; create it during setup with `FDW_ADDITIONAL_GRANTEE` or have Platform Operations
create it manually. The Java task does not create user mappings because they contain source database
credentials. Leave it blank to skip the extra grant.

For services using the Java task, `case_event_significant_items` is copied during `CUTOVER` after
events have caught up. It uses one set-based insert query joined through the migrated target events
up to the captured cutover event high-water mark, so the preload event walk is not restarted.

Required environment variables:

```bash
export DST_DSN='postgresql://target-user:target-pass@target-host:5432/target-db?sslmode=require'
export CASE_TYPE_IDS_SQL="'ET_EnglandWales','ET_Scotland','ET_Admin'"
```

Optional environment variables:

```bash
export DST_SCHEMA='ccd'          # defaults to ccd
export FDW_SCHEMA='fdw_stage'    # defaults to fdw_stage
export CASE_REVISION_OFFSET='1000000000'
export DELTA_SINCE=''            # empty means full load
```

`CASE_REVISION_OFFSET` is added to `ccd.case_data.case_revision` after event revisions are
recalculated. The decentralised Elasticsearch indexer uses `case_data.case_revision` as the
external Elasticsearch version, so the default high offset lets reindexed migrated cases overwrite
any existing central CCD Elasticsearch document with a lower revision. Migrated `case_event`
revisions remain sequential from `1` per case.

Validate before applying:

```bash
./scripts/migrate-ccd-data-fdw.sh
```

The validation checks:

* target database connectivity
* `pgcrypto` is installed
* the migration user can temporarily disable target triggers with `session_replication_role`
* `fdw_stage.case_data`, `fdw_stage.case_event`, and `fdw_stage.case_event_significant_items` exist
  as foreign tables
* source `case_data` count for the selected case types
* target `case_data`, `case_event`, and `case_event_significant_items` counts

Run a full migration:

```bash
unset DELTA_SINCE
./scripts/migrate-ccd-data-fdw.sh --apply
```

Run a delta migration:

```bash
export DELTA_SINCE='2026-04-30 10:00:00'
./scripts/migrate-ccd-data-fdw.sh --apply
```

## First-copy target cleanup

If you need to rerun a first-copy test and the target database only contains the migrating case
types, clean the target CCD tables before rerunning:

```sql
truncate table
  ccd.case_event_audit,
  ccd.es_queue,
  ccd.case_event_significant_items,
  ccd.case_event,
  ccd.case_data
restart identity cascade;
```

Only run this against a target database where those tables contain data for the migrating service
only.

## What the migration does

The migration script:

* temporarily drops the `case_event` FK and event revision unique index
* temporarily disables `case_event` user triggers so delta upserts do not write audit rows
* upserts `case_data` rows from `fdw_stage.case_data` with target triggers suppressed
* upserts `case_event` rows from `fdw_stage.case_event` for cases already loaded into `ccd.case_data`
* upserts `case_event_significant_items` rows linked to copied target events
* reruns `case_data` upsert to catch parent cases changed while events were copying
* reruns `case_event` upsert to catch events for cases loaded or updated by the second `case_data` pass
* reruns `case_event_significant_items` upsert for events caught by the second `case_event` pass
* recalculates `case_event.version` and `case_event.case_revision`
* updates `case_data.case_revision` to the max event revision plus `CASE_REVISION_OFFSET`, with
  target triggers suppressed
* checks for orphaned events and significant items
* restores the event revision unique index, FK and `case_event` user triggers
* resets `case_event_id_seq` and `case_event_significant_items_id_seq`
* runs final validation for counts, orphan events/items, duplicate event revisions and case revision alignment

If the script exits after dropping the FK and unique index, it attempts to restore them in an exit
handler before returning the original failure status. If automatic restoration fails, manual
database intervention is required before the application is unshuttered.

## Expected runtime

An AAT-style ET full-copy test migrated:

* 134,181 initial `case_data` rows
* 2,056,150 `case_event` rows
* 152 catch-up `case_data` rows

The run completed in about 1 hour 40 minutes. The slowest step was recalculating and updating event
revisions across the copied `case_event` rows.

## Post-migration checks

After a successful run, smoke test:

* view a migrated single case
* view a migrated multiple case
* view a migrated listing case
* edit a migrated case and confirm a new event can be created
* create a new case
* confirm search/indexing still works for newly created or edited cases
* confirm Work Allocation can read the case through the
  `caseworker-wa-task-configuration` punch-through role, where applicable
* confirm Work Allocation Service Bus messages are published, where applicable

## Automated regression test

`./gradlew verifyCcdMigration` uses the fixture under `scripts/migration-test/` to verify FDW
setup, full migration, delta migration, revision alignment, trigger suppression and constraint
restoration.
