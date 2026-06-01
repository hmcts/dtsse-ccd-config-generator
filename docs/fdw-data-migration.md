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
    end

    subgraph APP["Application database"]
      C["fdw_stage.case_data"]
      D["fdw_stage.case_event"]
      E["ccd.case_data"]
      F["ccd.case_event"]
    end

    A -->|postgres_fdw| C
    B -->|postgres_fdw| D
    C -->|insert/update| E
    D -->|insert/update| F
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
* a source CCD database user with read access to `case_data` and `case_event`
* a target application database user with write access to `ccd.case_data` and `ccd.case_event`
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
```

Optional environment variables:

```bash
export DST_SCHEMA='ccd'                    # defaults to ccd
export FDW_SCHEMA='fdw_stage'              # defaults to fdw_stage
export FDW_SERVER='src_ccd_server'         # defaults to src_ccd_server
export LOCAL_USER_SQL='current_user'       # role that will run the migration
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
* a user mapping using `SRC_USER` and `SRC_PASSWORD`
* foreign tables:
  * `fdw_stage.case_data`
  * `fdw_stage.case_event`
* grants for `LOCAL_USER_SQL`

## Phase 2: Run the migration

The migration script only reads from the FDW foreign tables. It does not create or write to
`fdw_stage`.

Required environment variables:

```bash
export DST_DSN='postgresql://target-user:target-pass@target-host:5432/target-db?sslmode=require'
export CASE_TYPE_IDS_SQL="'ET_EnglandWales','ET_Scotland','ET_Admin'"
```

Optional environment variables:

```bash
export DST_SCHEMA='ccd'          # defaults to ccd
export FDW_SCHEMA='fdw_stage'    # defaults to fdw_stage
export DELTA_SINCE=''            # empty means full load
```

Validate before applying:

```bash
./scripts/migrate-ccd-data-fdw.sh
```

The validation checks:

* target database connectivity
* `pgcrypto` is installed
* the migration user can temporarily disable target triggers with `session_replication_role`
* `fdw_stage.case_data` and `fdw_stage.case_event` exist as foreign tables
* source `case_data` count for the selected case types
* target `case_data` and `case_event` counts

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
  ccd.case_event,
  ccd.case_data
restart identity cascade;
```

Only run this against a target database where those tables contain data for the migrating service
only.

## What the migration does

The migration script:

* temporarily drops the `case_event` FK and event revision unique index
* upserts `case_data` rows from `fdw_stage.case_data` with target triggers suppressed
* upserts `case_event` rows from `fdw_stage.case_event` for cases already loaded into `ccd.case_data`
* reruns `case_data` upsert to catch parent cases changed while events were copying
* recalculates `case_event.version` and `case_event.case_revision`
* updates `case_data.case_revision` with target triggers suppressed
* checks for orphaned events
* restores the event revision unique index and FK
* resets `case_event_id_seq`
* runs final validation for counts, orphan events, duplicate event revisions and case revision alignment

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
