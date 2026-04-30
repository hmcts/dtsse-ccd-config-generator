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
#   public.case_data
#   public.case_event
#
# Full run:
#   ./migrate-ccd-data-fdw.sh --apply
#
# Delta run:
#   DELTA_SINCE="2026-04-30 10:00:00" ./migrate-ccd-data-fdw.sh --apply
# ------------------------------------------------------------------------------

DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:5432/postgres}"

SRC_HOST="${SRC_HOST:-localhost}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_DB="${SRC_DB:-datastore}"
SRC_SCHEMA="${SRC_SCHEMA:-public}"
SRC_USER="${SRC_USER:-postgres}"
SRC_PASSWORD="${SRC_PASSWORD:-postgres}"

DST_SCHEMA="${DST_SCHEMA:-ccd}"
FDW_SCHEMA="${FDW_SCHEMA:-fdw_stage}"
FDW_SERVER="${FDW_SERVER:-src_ccd_server}"

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

Environment variables:
  DST_DSN
  SRC_HOST
  SRC_PORT
  SRC_DB
  SRC_SCHEMA
  SRC_USER
  SRC_PASSWORD
  CASE_TYPE_IDS_SQL
  DELTA_SINCE optional, e.g. "2026-04-30 10:00:00"

Example:
  export DST_DSN='postgresql://user:pass@dest.postgres.database.azure.com:5432/appdb?sslmode=require'
  export SRC_HOST='source.postgres.database.azure.com'
  export SRC_PORT='5432'
  export SRC_DB='ccd_data_store'
  export SRC_SCHEMA='public'
  export SRC_USER='readonly_user'
  export SRC_PASSWORD='...'
  export CASE_TYPE_IDS_SQL="'ET_EnglandWales','ET_Scotland','ET_Admin','ET_EnglandWales_Multiple','ET_Scotland_Multiple','ET_EnglandWales_Listings','ET_Scotland_Listings'"

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
    --set=src_host="$SRC_HOST" \
    --set=src_port="$SRC_PORT" \
    --set=src_db="$SRC_DB" \
    --set=src_schema="$SRC_SCHEMA" \
    --set=src_user="$SRC_USER" \
    --set=src_password="$SRC_PASSWORD" \
    --set=dst_schema="$DST_SCHEMA" \
    --set=fdw_schema="$FDW_SCHEMA" \
    --set=fdw_server="$FDW_SERVER" \
    --set=case_type_ids="$CASE_TYPE_IDS_SQL" \
    --set=delta_since="$DELTA_SINCE" \
    "$@"
}

validate_connection() {
  log "Validating destination connection..."
  psql_dst --quiet -c "select 1;" >/dev/null
}

setup_extensions() {
  log "Creating required extensions..."
  psql_dst --quiet <<SQL
create extension if not exists postgres_fdw;
create extension if not exists pgcrypto;
SQL
}

setup_fdw() {
  log "Creating FDW server, user mapping and foreign tables..."

  psql_dst <<'SQL'
create schema if not exists :fdw_schema;

drop foreign table if exists :fdw_schema.case_event;
drop foreign table if exists :fdw_schema.case_data;

drop server if exists :fdw_server cascade;

create server :fdw_server
foreign data wrapper postgres_fdw
options (
  host :'src_host',
  port :'src_port',
  dbname :'src_db',
  sslmode 'require'
);

create user mapping for current_user
server :fdw_server
options (
  user :'src_user',
  password :'src_password'
);

create foreign table :fdw_schema.case_data (
  reference bigint,
  version integer,
  created_date timestamp without time zone,
  security_classification ccd.securityclassification,
  last_state_modified_date timestamp without time zone,
  resolved_ttl date,
  last_modified timestamp without time zone,
  jurisdiction varchar(255),
  case_type_id varchar(255),
  state varchar(255),
  data jsonb,
  supplementary_data jsonb,
  id bigint,
  case_revision bigint
)
server :fdw_server
options (
  schema_name :'src_schema',
  table_name 'case_data'
);

create foreign table :fdw_schema.case_event (
  id bigint,
  created_date timestamp without time zone,
  security_classification ccd.securityclassification,
  case_data_id bigint,
  case_type_version integer,
  event_id varchar(70),
  summary varchar(1024),
  description varchar(65536),
  user_id varchar(64),
  case_type_id varchar(255),
  state_id varchar(255),
  data jsonb,
  user_first_name varchar(255),
  user_last_name varchar(255),
  event_name varchar(30),
  state_name varchar(255),
  proxied_by varchar(64),
  proxied_by_first_name varchar(255),
  proxied_by_last_name varchar(255),
  idempotency_key uuid,
  version integer,
  case_revision bigint
)
server :fdw_server
options (
  schema_name :'src_schema',
  table_name 'case_event'
);
SQL
}

validate_counts() {
  log "Source/target counts via FDW..."

  psql_dst <<'SQL'
select :'delta_since' as delta_since;

select 'source case_data' as table_name, count(*)
from fdw_stage.case_data
where case_type_id in (:case_type_ids)
and (
  :'delta_since' = ''
  or last_modified >= :'delta_since'::timestamp
);

select 'source case_event' as table_name, count(*)
from fdw_stage.case_event ce
join fdw_stage.case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id in (:case_type_ids)
and (
  :'delta_since' = ''
  or ce.created_date >= :'delta_since'::timestamp
);

select 'target case_data' as table_name, count(*)
from ccd.case_data
where case_type_id in (:case_type_ids);

select 'target case_event' as table_name, count(*)
from ccd.case_event
where case_type_id in (:case_type_ids);
SQL
}

prepare_target() {
  log "Dropping constraints/indexes that block placeholder event revisions..."

  psql_dst <<'SQL'
alter table ccd.case_event
drop constraint if exists case_event_case_data_id_fkey;

drop index if exists ccd.idx_case_event_case_data_revision_unique;
SQL
}

load_case_data() {
  log "Loading/upserting case_data..."

  psql_dst <<'SQL'
insert into ccd.case_data (
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
from fdw_stage.case_data
where case_type_id in (:case_type_ids)
and (
    :'delta_since' = ''
    or last_modified >= :'delta_since'::timestamp
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
    ccd.case_data.version,
    ccd.case_data.last_modified,
    ccd.case_data.case_revision,
    ccd.case_data.state,
    ccd.case_data.data,
    ccd.case_data.supplementary_data
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
insert into ccd.case_event (
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
from fdw_stage.case_event ce
join fdw_stage.case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id in (:case_type_ids)
and (
    :'delta_since' = ''
    or ce.created_date >= :'delta_since'::timestamp
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
on ccd.case_event (case_data_id, id);

update ccd.case_event t
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
    from ccd.case_event
    where case_type_id in (:case_type_ids)
) s
where t.id = s.id;

update ccd.case_data cd
set case_revision = evt.event_count
from (
    select
        case_data_id,
        count(*)::bigint as event_count
    from ccd.case_event
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
from ccd.case_event e
left join ccd.case_data d
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
on ccd.case_event (case_data_id, case_revision);

alter table ccd.case_event
add constraint case_event_case_data_id_fkey
foreign key (case_data_id)
references ccd.case_data(id)
on delete cascade;
SQL
}

reset_sequences() {
  log "Resetting case_event_id_seq..."

  psql_dst <<'SQL'
select setval(
  'ccd.case_event_id_seq',
  (select coalesce(max(id), 1) from ccd.case_event),
  true
);
SQL
}

final_validation() {
  log "Final validation..."

  psql_dst <<'SQL'
select 'target case_data by case_type' as check_name, case_type_id, count(*)
from ccd.case_data
where case_type_id in (:case_type_ids)
group by case_type_id
order by case_type_id;

select 'target case_event by case_type' as check_name, case_type_id, count(*)
from ccd.case_event
where case_type_id in (:case_type_ids)
group by case_type_id
order by case_type_id;

select 'orphan case_event rows' as check_name, count(*)
from ccd.case_event e
left join ccd.case_data d
  on d.id = e.case_data_id
where d.id is null
  and e.case_type_id in (:case_type_ids);

select 'duplicate event revisions' as check_name, count(*)
from (
    select case_data_id, case_revision
    from ccd.case_event
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
  setup_extensions
  setup_fdw
  validate_counts

  if [[ "$DO_APPLY" != "true" ]]; then
    log "Validation complete. Re-run with --apply to perform migration."
    exit 0
  fi

  log "Starting FDW migration into final ccd.case_data and ccd.case_event tables..."

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
