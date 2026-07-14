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
export SRC_SSLMODE='require'
```

Optional environment variables:

```bash
export DST_SCHEMA='ccd'                    # defaults to ccd
export FDW_SCHEMA='fdw_stage'              # defaults to fdw_stage
export FDW_SERVER='src_ccd_server'         # defaults to src_ccd_server
export LOCAL_USER_SQL='current_user'       # role that will run the migration
export FDW_ADDITIONAL_GRANTEE_SQL='"DTS JIT Access et DB Reader SC"'
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
* when `FDW_ADDITIONAL_GRANTEE_SQL` is set, another user mapping for that role using the same
  source credentials
* foreign tables:
  * `fdw_stage.case_data`
  * `fdw_stage.case_event`
  * `fdw_stage.case_event_significant_items`

The foreign tables are created with `fetch_size '10000'` so large reads do not use the
`postgres_fdw` default of 100 rows per cursor fetch. If the FDW objects were created before this
option existed, recreate them with `setup-ccd-data-fdw.sh --apply` before running a large migration.
* grants for `LOCAL_USER_SQL`
* when `FDW_ADDITIONAL_GRANTEE_SQL` is set, grants for that additional role

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
the FDW server; create it during setup with `FDW_ADDITIONAL_GRANTEE_SQL` or have Platform Operations
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

## Automated regression test

`./gradlew verifyCcdMigration` runs the FDW migration test as well as the classic migration test.
The FDW test uses the shared fixture under `scripts/migration-test/` to verify setup, full
migration, delta migration, revision alignment, trigger suppression and constraint restoration.
