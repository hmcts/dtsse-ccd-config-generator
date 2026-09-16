# Clean up CCD data migration database access

Use `scripts/cleanup-ccd-data-fdw.sh` after an environment has completed CCD decentralisation and
its migration rollback window has closed. The script removes the application database's temporary
FDW connection to the central CCD database without changing migrated case data.

## What is removed

- the migration FDW server, normally `src_ccd_server`;
- every user mapping on that server, including stored source CCD credentials;
- the three migration foreign tables;
- the `fdw_stage` schema.

These are local database catalogue objects. Foreign tables do not store the central CCD rows, and
dropping them does not execute a drop against the source database.

The script retains migrated data under the target `ccd` schema and the `postgres_fdw` and
`pgcrypto` extensions, which may be shared by other database features.

## Preconditions

Before applying the cleanup, confirm that:

1. migrated cases can be viewed, edited and found through search;
2. new cases and events are being written to the application database;
3. migration reconciliation is complete;
4. the agreed rollback period has expired;
5. no retained migration progress row has a status other than `COMPLETE`.

The script additionally refuses to proceed when the staging schema contains an unexpected
relation, the selected FDW server backs a foreign table outside that schema, a staging table uses a
different server, or the connected database role does not own the objects.

## Validate

Connect using the database owner or another role that owns the FDW server and schema. The default
mode validates and lists the local objects without changing them:

```bash
export DST_DSN='postgresql://target-user:target-pass@target-host:5432/target-db?sslmode=require'
export FDW_SCHEMA='fdw_stage'
export FDW_SERVER='src_ccd_server'

./scripts/cleanup-ccd-data-fdw.sh
```

Validation lists mapped user names but never displays credential-bearing user-mapping options.

## Apply

After reviewing the validation output, apply the cleanup:

```bash
./scripts/cleanup-ccd-data-fdw.sh --apply
```

The operation is idempotent, so it is safe when an application's Flyway migration has already
removed its staging foreign tables and schema.

## Apply through application Flyway

For cleanup as part of an application deployment, copy the SDK's
[`VXXXX__remove_ccd_migration_fdw.sql`](../sdk/ccd-data-migration-support/src/main/resources/ccd-data-migration-db/examples/VXXXX__remove_ccd_migration_fdw.sql)
example into the application's Flyway migration directory and replace `VXXXX` with its next
migration version. The example applies the same ownership, shared-object, unexpected-relation and
incomplete-progress checks before removing the server, mappings, foreign tables and staging schema.

The example is outside the SDK's configured Flyway migration location, so consuming the library
does not run the cleanup automatically. Each application must opt in after its reconciliation and
rollback window are complete.

Source database accounts, network allow-list entries and Azure extension allow-list settings remain
separate Platform Operations cleanup actions. Remove `postgres_fdw` from `azure.extensions` only if
no database on that PostgreSQL server uses it; extension allow-list changes are server-wide.
