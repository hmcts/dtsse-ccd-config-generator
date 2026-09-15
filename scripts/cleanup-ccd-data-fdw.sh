#!/usr/bin/env bash

set -euo pipefail

DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:5432/postgres}"
FDW_SCHEMA="${FDW_SCHEMA:-fdw_stage}"
FDW_SERVER="${FDW_SERVER:-src_ccd_server}"
DO_APPLY=false

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [--apply]

Default mode validates and lists the FDW objects that would be removed.
Use --apply to drop the migration FDW server, its user mappings and foreign tables, and the FDW schema.

Environment variables:
  DST_DSN     target application database connection string
  FDW_SCHEMA  migration staging schema; defaults to fdw_stage
  FDW_SERVER  migration foreign server; defaults to src_ccd_server

Example:
  export DST_DSN='postgresql://user:pass@dest.postgres.database.azure.com:5432/appdb?sslmode=require'
  ./scripts/cleanup-ccd-data-fdw.sh
  ./scripts/cleanup-ccd-data-fdw.sh --apply
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --apply)
        DO_APPLY=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        log "ERROR: Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql is required on PATH"
    exit 1
  fi
}

psql_dst() {
  psql "$DST_DSN" \
    --set=ON_ERROR_STOP=on \
    --no-psqlrc \
    --set=fdw_schema="$FDW_SCHEMA" \
    --set=fdw_server="$FDW_SERVER" \
    "$@"
}

validate_cleanup() {
  log "Validating CCD data migration FDW cleanup..."
  psql_dst <<'SQL'
select set_config('ccd_cleanup.fdw_schema', :'fdw_schema', false),
       set_config('ccd_cleanup.fdw_server', :'fdw_server', false);

do $$
declare
  unexpected_relations text;
  external_server_relations text;
  wrong_server_relations text;
  incomplete_tasks text;
begin
  if exists (
    select 1 from pg_foreign_server s
    where s.srvname = current_setting('ccd_cleanup.fdw_server')
      and not pg_has_role(current_user, s.srvowner, 'USAGE')
  ) then
    raise exception 'Current user % does not own FDW server %',
      current_user, current_setting('ccd_cleanup.fdw_server');
  end if;

  if exists (
    select 1 from pg_namespace n
    where n.nspname = current_setting('ccd_cleanup.fdw_schema')
      and not pg_has_role(current_user, n.nspowner, 'USAGE')
  ) then
    raise exception 'Current user % does not own FDW schema %',
      current_user, current_setting('ccd_cleanup.fdw_schema');
  end if;

  select string_agg(format('%I.%I', n.nspname, c.relname), ', ' order by c.relname)
  into unexpected_relations
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = current_setting('ccd_cleanup.fdw_schema')
    and not (c.relkind = 'f' and c.relname in ('case_data', 'case_event', 'case_event_significant_items'));

  if unexpected_relations is not null then
    raise exception 'FDW schema contains unexpected relations: %', unexpected_relations;
  end if;

  select string_agg(format('%I.%I', n.nspname, c.relname), ', ' order by n.nspname, c.relname)
  into external_server_relations
  from pg_foreign_table ft
  join pg_class c on c.oid = ft.ftrelid
  join pg_namespace n on n.oid = c.relnamespace
  join pg_foreign_server s on s.oid = ft.ftserver
  where s.srvname = current_setting('ccd_cleanup.fdw_server')
    and n.nspname <> current_setting('ccd_cleanup.fdw_schema');

  if external_server_relations is not null then
    raise exception 'FDW server is shared by tables outside the migration schema: %', external_server_relations;
  end if;

  select string_agg(format('%I.%I uses %I', n.nspname, c.relname, s.srvname), ', ' order by c.relname)
  into wrong_server_relations
  from pg_foreign_table ft
  join pg_class c on c.oid = ft.ftrelid
  join pg_namespace n on n.oid = c.relnamespace
  join pg_foreign_server s on s.oid = ft.ftserver
  where n.nspname = current_setting('ccd_cleanup.fdw_schema')
    and s.srvname <> current_setting('ccd_cleanup.fdw_server');

  if wrong_server_relations is not null then
    raise exception 'Migration schema contains tables on a different FDW server: %', wrong_server_relations;
  end if;

  if to_regclass('ccd.ccd_data_migration_progress') is not null then
    execute $query$
      select string_agg(task_name || '=' || status, ', ' order by task_name)
      from ccd.ccd_data_migration_progress
      where status <> 'COMPLETE'
    $query$ into incomplete_tasks;

    if incomplete_tasks is not null then
      raise exception 'CCD data migration has incomplete progress rows: %', incomplete_tasks;
    end if;
  end if;
end $$;

select s.srvname as fdw_server,
       coalesce(string_agg(m.usename, ', ' order by m.usename), '(no user mappings)') as mapped_users
from pg_foreign_server s
left join pg_user_mappings m on m.srvid = s.oid
where s.srvname = :'fdw_server'
group by s.srvname;

select n.nspname as schema_name, c.relname as foreign_table, s.srvname as fdw_server
from pg_foreign_table ft
join pg_class c on c.oid = ft.ftrelid
join pg_namespace n on n.oid = c.relnamespace
join pg_foreign_server s on s.oid = ft.ftserver
where n.nspname = :'fdw_schema'
order by c.relname;
SQL
}

apply_cleanup() {
  log "Removing CCD data migration FDW objects..."
  psql_dst <<'SQL'
select set_config('ccd_cleanup.fdw_schema', :'fdw_schema', false),
       set_config('ccd_cleanup.fdw_server', :'fdw_server', false);

do $$
begin
  if exists (
    select 1 from pg_foreign_server
    where srvname = current_setting('ccd_cleanup.fdw_server')
  ) then
    execute format('drop server %I cascade', current_setting('ccd_cleanup.fdw_server'));
  end if;

  execute format('drop schema if exists %I', current_setting('ccd_cleanup.fdw_schema'));
end $$;
SQL
  log "FDW cleanup complete. Source tables and migrated target data were not modified."
}

parse_args "$@"
require_psql
validate_cleanup

if [[ "$DO_APPLY" == "true" ]]; then
  apply_cleanup
else
  log "Validation complete. Re-run with --apply to remove these local FDW objects."
fi
