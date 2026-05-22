#!/usr/bin/env bash

set -euo pipefail

# ------------------------------------------------------------------------------
# FDW-based CCD data migration
#
# Populates:
#   ccd.case_data
#   ccd.case_event
#
# From remote source tables:
#   FDW_SCHEMA.case_data
#   FDW_SCHEMA.case_event
#
# Requires the FDW objects to have already been created by:
#   ./setup-ccd-data-fdw.sh --apply
#
# Full run:
#   ./migrate-ccd-data-fdw.sh --apply
#
# Delta run:
#   DELTA_SINCE="2026-04-30 10:00:00" ./migrate-ccd-data-fdw.sh --apply
# ------------------------------------------------------------------------------

DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:5432/postgres}"

DST_SCHEMA="${DST_SCHEMA:-ccd}"
FDW_SCHEMA="${FDW_SCHEMA:-fdw_stage}"

CASE_TYPE_IDS_SQL="${CASE_TYPE_IDS_SQL:-'TEST_CASE_TYPE'}"

# Optional. Empty means full load.
# Example: DELTA_SINCE="2026-04-30 10:00:00"
DELTA_SINCE="${DELTA_SINCE:-}"

DO_APPLY=false

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [--apply]

Default mode validates configuration and counts data.
Use --apply to perform the migration.

Requires FDW foreign tables to already exist. Run setup first:
  ./scripts/setup-ccd-data-fdw.sh --apply

Environment variables:
  DST_DSN
  DST_SCHEMA
  FDW_SCHEMA
  CASE_TYPE_IDS_SQL
  DELTA_SINCE optional, e.g. "2026-04-30 10:00:00"

Example:
  export DST_DSN='postgresql://user:pass@dest.postgres.database.azure.com:5432/appdb?sslmode=require'
  export CASE_TYPE_IDS_SQL="'ET_EnglandWales','ET_Scotland','ET_Admin'"

  ./scripts/migrate-ccd-data-fdw.sh
  ./scripts/migrate-ccd-data-fdw.sh --apply

Delta:
  DELTA_SINCE="2026-04-30 10:00:00" ./scripts/migrate-ccd-data-fdw.sh --apply
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
    --set=dst_schema="$DST_SCHEMA" \
    --set=fdw_schema="$FDW_SCHEMA" \
    --set=case_type_ids="$CASE_TYPE_IDS_SQL" \
    --set=delta_since="$DELTA_SINCE" \
    "$@"
}

validate_connection() {
  log "Validating destination connection..."
  psql_dst --quiet -c "select 1;" >/dev/null
}

validate_fdw_ready() {
  log "Validating required extensions and FDW foreign tables..."

  local pgcrypto_count
  local missing_table_count

  pgcrypto_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from pg_extension
where extname = 'pgcrypto';
SQL
)"

  if [[ "$pgcrypto_count" != "1" ]]; then
    log "ERROR: pgcrypto extension is missing. Run ./scripts/setup-ccd-data-fdw.sh --apply first."
    exit 1
  fi

  missing_table_count="$(psql_dst --quiet -tA <<'SQL'
select 2 - count(*)
from (
  select c.relname
  from pg_foreign_table ft
  join pg_class c on c.oid = ft.ftrelid
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = :'fdw_schema'
    and c.relname in ('case_data', 'case_event')
) as fdw_tables;
SQL
)"

  if [[ "$missing_table_count" != "0" ]]; then
    log "ERROR: FDW foreign tables are missing in schema ${FDW_SCHEMA}."
    log "Run ./scripts/setup-ccd-data-fdw.sh --apply first."
    exit 1
  fi
}

validate_counts() {
  log "Source/target counts..."

  psql_dst <<'SQL'
select :'delta_since' as delta_since;

select 'source case_data' as table_name, count(*)::text as count
from :fdw_schema.case_data
where case_type_id in (:case_type_ids)
and (
  nullif(:'delta_since', '') is null
  or last_modified >= nullif(:'delta_since', '')::timestamp
);

select 'source case_event' as table_name, 'unknown - slow query' as count;

select 'target case_data' as table_name, count(*)
from :dst_schema.case_data
where case_type_id in (:case_type_ids);

select 'target case_event' as table_name, count(*)
from :dst_schema.case_event
where case_type_id in (:case_type_ids);
SQL
}

prepare_target() {
  log "Dropping constraints/indexes that block placeholder event revisions..."

  psql_dst <<'SQL'
alter table :dst_schema.case_event
drop constraint if exists case_event_case_data_id_fkey;

drop index if exists :dst_schema.idx_case_event_case_data_revision_unique;
SQL
}

load_case_data() {
  log "Loading/upserting case_data..."

  psql_dst <<'SQL'
insert into :dst_schema.case_data (
    reference,
    version,
    created_date,
    security_classification,
    last_state_modified_date,
    resolved_ttl,
    last_modified,
    jurisdiction,
    case_type_id,
    state,
    data,
    supplementary_data,
    id,
    case_revision
)
select
    reference,
    version,
    created_date,
    security_classification,
    last_state_modified_date,
    resolved_ttl,
    last_modified,
    jurisdiction,
    case_type_id,
    state,
    data,
    coalesce(supplementary_data, jsonb_build_object()),
    id,
    version
from :fdw_schema.case_data
where case_type_id in (:case_type_ids)
and (
    nullif(:'delta_since', '') is null
    or last_modified >= nullif(:'delta_since', '')::timestamp
)
on conflict (reference) do update
set
    version = excluded.version,
    created_date = excluded.created_date,
    security_classification = excluded.security_classification,
    last_state_modified_date = excluded.last_state_modified_date,
    resolved_ttl = excluded.resolved_ttl,
    last_modified = excluded.last_modified,
    jurisdiction = excluded.jurisdiction,
    case_type_id = excluded.case_type_id,
    state = excluded.state,
    data = excluded.data,
    supplementary_data = excluded.supplementary_data,
    id = excluded.id,
    case_revision = excluded.case_revision
where (
    :dst_schema.case_data.version,
    :dst_schema.case_data.last_modified,
    :dst_schema.case_data.case_revision,
    :dst_schema.case_data.state,
    :dst_schema.case_data.data,
    :dst_schema.case_data.supplementary_data
) is distinct from (
    excluded.version,
    excluded.last_modified,
    excluded.case_revision,
    excluded.state,
    excluded.data,
    excluded.supplementary_data
);
SQL
}

load_case_events() {
  log "Loading/upserting case_event with temporary version/case_revision values..."

  psql_dst <<'SQL'
insert into :dst_schema.case_event (
    id,
    created_date,
    security_classification,
    case_data_id,
    case_type_version,
    event_id,
    summary,
    description,
    user_id,
    case_type_id,
    state_id,
    data,
    user_first_name,
    user_last_name,
    event_name,
    state_name,
    proxied_by,
    proxied_by_first_name,
    proxied_by_last_name,
    idempotency_key,
    version,
    case_revision
)
select
    ce.id,
    ce.created_date,
    ce.security_classification,
    ce.case_data_id,
    ce.case_type_version,
    ce.event_id,
    ce.summary,
    ce.description,
    ce.user_id,
    ce.case_type_id,
    ce.state_id,
    ce.data,
    ce.user_first_name,
    ce.user_last_name,
    ce.event_name,
    ce.state_name,
    ce.proxied_by,
    ce.proxied_by_first_name,
    ce.proxied_by_last_name,
    gen_random_uuid(),
    0,
    0
from :fdw_schema.case_event ce
where ce.case_type_id in (:case_type_ids)
and (
    nullif(:'delta_since', '') is null
    or ce.created_date >= nullif(:'delta_since', '')::timestamp
)
on conflict (id) do update
set
    created_date = excluded.created_date,
    security_classification = excluded.security_classification,
    case_data_id = excluded.case_data_id,
    case_type_version = excluded.case_type_version,
    event_id = excluded.event_id,
    summary = excluded.summary,
    description = excluded.description,
    user_id = excluded.user_id,
    case_type_id = excluded.case_type_id,
    state_id = excluded.state_id,
    data = excluded.data,
    user_first_name = excluded.user_first_name,
    user_last_name = excluded.user_last_name,
    event_name = excluded.event_name,
    state_name = excluded.state_name,
    proxied_by = excluded.proxied_by,
    proxied_by_first_name = excluded.proxied_by_first_name,
    proxied_by_last_name = excluded.proxied_by_last_name;
SQL
}

recalculate_revisions() {
  log "Recalculating case_event.version and case_event.case_revision locally..."

  psql_dst <<'SQL'
create index if not exists idx_tmp_case_event_case_data_id_id
on :dst_schema.case_event (case_data_id, id);

update :dst_schema.case_event t
set
    version = s.rn::int,
    case_revision = s.rn::bigint
from (
    select
        id,
        row_number() over (
            partition by case_data_id
            order by id
        ) as rn
    from :dst_schema.case_event
    where case_type_id in (:case_type_ids)
) s
where t.id = s.id;

update :dst_schema.case_data cd
set case_revision = evt.event_count
from (
    select
        case_data_id,
        count(*)::bigint as event_count
    from :dst_schema.case_event
    where case_type_id in (:case_type_ids)
    group by case_data_id
) evt
where cd.id = evt.case_data_id;
SQL
}

validate_no_orphans() {
  log "Checking for orphaned case_event rows..."

  local orphan_count

  orphan_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from :dst_schema.case_event e
left join :dst_schema.case_data d
  on d.id = e.case_data_id
where d.id is null
  and e.case_type_id in (:case_type_ids);
SQL
)"

  if [[ "$orphan_count" != "0" ]]; then
    log "ERROR: Found ${orphan_count} orphaned case_event rows. Not restoring FK."
    exit 1
  fi
}

restore_constraints() {
  log "Restoring event revision unique index and FK..."

  psql_dst <<'SQL'
create unique index if not exists idx_case_event_case_data_revision_unique
on :dst_schema.case_event (case_data_id, case_revision);

alter table :dst_schema.case_event
add constraint case_event_case_data_id_fkey
foreign key (case_data_id)
references :dst_schema.case_data(id)
on delete cascade;
SQL
}

reset_sequences() {
  log "Resetting case_event_id_seq..."

  psql_dst <<'SQL'
select setval(
  format('%I.case_event_id_seq', :'dst_schema')::regclass,
  (select coalesce(max(id), 1) from :dst_schema.case_event),
  true
);
SQL
}

final_validation() {
  log "Final validation..."

  psql_dst <<'SQL'
select 'target case_data by case_type' as check_name, case_type_id, count(*)
from :dst_schema.case_data
where case_type_id in (:case_type_ids)
group by case_type_id
order by case_type_id;

select 'target case_event by case_type' as check_name, case_type_id, count(*)
from :dst_schema.case_event
where case_type_id in (:case_type_ids)
group by case_type_id
order by case_type_id;

select 'orphan case_event rows' as check_name, count(*)
from :dst_schema.case_event e
left join :dst_schema.case_data d
  on d.id = e.case_data_id
where d.id is null
  and e.case_type_id in (:case_type_ids);

select 'duplicate event revisions' as check_name, count(*)
from (
    select case_data_id, case_revision
    from :dst_schema.case_event
    where case_type_id in (:case_type_ids)
    group by case_data_id, case_revision
    having count(*) > 1
) duplicates;
SQL
}

main() {
  parse_args "$@"
  require_psql

  log "DELTA_SINCE=${DELTA_SINCE:-<empty: full load>}"

  validate_connection
  validate_fdw_ready
  validate_counts

  if [[ "$DO_APPLY" != "true" ]]; then
    log "Validation complete. Re-run with --apply to perform migration."
    exit 0
  fi

  log "Starting FDW migration into final ${DST_SCHEMA}.case_data and ${DST_SCHEMA}.case_event tables..."

  prepare_target
  load_case_data
  load_case_events

  # Important: catch parent cases created while events were being copied.
  load_case_data

  recalculate_revisions
  validate_no_orphans
  restore_constraints
  reset_sequences
  final_validation

  log "FDW migration completed successfully."
}

main "$@"
